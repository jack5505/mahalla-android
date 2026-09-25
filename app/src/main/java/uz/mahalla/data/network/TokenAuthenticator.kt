package uz.mahalla.data.network

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.dataOrNull
import uz.mahalla.data.network.AuthInterceptor.Companion.BEARER_PREFIX
import uz.mahalla.data.network.AuthInterceptor.Companion.HEADER_AUTHORIZATION
import uz.mahalla.data.device.DeviceInfoProvider
import uz.mahalla.data.location.RequestLocationProvider
import uz.mahalla.data.network.auth.AuthApi
import uz.mahalla.data.network.auth.RefreshTokenRequest
import uz.mahalla.data.network.auth.toDto
import uz.mahalla.data.prefs.Session
import uz.mahalla.data.prefs.SessionStore
import uz.mahalla.data.session.LocalUserDataCleaner
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Обновление токена по 401 (эпик 1.3).
 *
 * OkHttp сам повторяет запрос с [Request], который мы вернём, и сам не даст
 * зациклиться, если вернуть `null`. Дополнительно:
 *  - весь блок под `synchronized` — параллельные 401 не должны сделать
 *    несколько refresh-запросов;
 *  - если пока мы ждали лока токен уже обновил кто-то другой, просто
 *    повторяем запрос с новым токеном;
 *  - больше [MAX_ATTEMPTS] попыток не делаем — иначе бесконечный цикл при
 *    сервере, который отдаёт 401 на валидный токен.
 *
 * Стёртая сессия — не только локальное дело сетевого слоя: приложение выше
 * продолжало бы показывать экраны, на которые ему больше нечем ходить. Поэтому
 * о смерти сессии сообщается наверх через [SessionExpiry], и поэтому же она
 * стирается не при любом провале refresh, а только когда сервер ответил на
 * него 401 — см. `rejectsSession` (issue #138).
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val sessionStore: SessionStore,
    private val sessionExpiry: SessionExpiry,
    private val authApi: AuthApi,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val locationProvider: RequestLocationProvider,
    private val clock: Clock,
    private val localUserDataCleaner: LocalUserDataCleaner,
) : Authenticator {

    /**
     * Подряд идущие провалы refresh, из которых не ясно, жив ли токен — не
     * явный отказ (401) и не сетевой сбой (issue #198). Читается и пишется
     * только внутри `synchronized(this)` в [authenticate].
     *
     * Живёт в памяти и не переживает перезапуск процесса: подменённый ответ
     * чужого прокси (issue #138) конечен и с новым процессом не связан, а вот
     * сломанный контракт ломается заново на каждом запуске, так что досчитает
     * и без сохранения на диск.
     */
    private var consecutiveAmbiguousRefreshFailures = 0

    /**
     * Момент последнего засчитанного [isAmbiguousContractFailure]. Несколько
     * экранов, разом упёршихся в 401 на холодном старте, берут лок по
     * очереди и каждый делает свой собственный refresh — если чужой прокси
     * (issue #138) в этот момент подменяет ответ, все они получат одно и то
     * же тело почти одновременно. Это один инцидент прокси, а не
     * [MAX_AMBIGUOUS_REFRESH_FAILURES] разных провалов контракта подряд, и
     * счётчик не должен путать одно с другим (issue #301) — поэтому ответ
     * считается только если он пришёл не раньше [MIN_AMBIGUOUS_REFRESH_GAP]
     * после предыдущего засчитанного.
     */
    private var lastAmbiguousRefreshFailureAt: Instant? = null

    /**
     * Обнулить счётчик неоднозначных провалов refresh. Сессию пишут и стирают
     * ещё в нескольких местах мимо этого класса — `AuthRepository.signIn()`
     * (а значит и повторный вход после `logout()`/`clearLocalIdentity()`).
     * Без сброса застрявший на 1–2 счёт из прежней сессии добивает себя до
     * порога первым же неоднозначным ответом в новой и стирает её мгновенно —
     * тот же цикл «вход → платный SMS → моментальный выход», от которого
     * защищает issue #138 (issue #309).
     */
    fun reset() {
        synchronized(this) {
            consecutiveAmbiguousRefreshFailures = 0
            lastAmbiguousRefreshFailureAt = null
        }
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        if (attemptCount(response) >= MAX_ATTEMPTS) return null

        val staleToken = response.request.header(HEADER_AUTHORIZATION)
            ?.removePrefix(BEARER_PREFIX)

        return synchronized(this) {
            val session = runBlocking { sessionStore.current() } ?: return@synchronized null

            if (session.accessToken != staleToken) {
                // Токен уже обновлён параллельным запросом — refresh не нужен.
                return@synchronized response.request.withBearer(session.accessToken)
            }

            val refresh = runBlocking {
                apiCall {
                    // Устройство и координаты бэкенд требует и здесь: refresh
                    // для него — это продление сессии конкретного устройства.
                    // Собираются внутри `apiCall`: их сбой — это «спросить не
                    // удалось», а не исключение наружу из `Authenticator`.
                    val device = deviceInfoProvider.current().toDto()
                    val location = locationProvider.current()
                    authApi.refresh(
                        RefreshTokenRequest(
                            refreshToken = session.refreshToken,
                            device = device,
                            lat = location.latitude,
                            lng = location.longitude,
                        ),
                    ).payload()
                }
            }

            val refreshed = refresh.dataOrNull()
            val tokens = refreshed?.tokens
            val accessToken = tokens?.accessToken?.takeIf { it.isNotBlank() }
            val refreshToken = tokens?.refreshToken?.takeIf { it.isNotBlank() }
            if (accessToken == null || refreshToken == null) {
                if (refresh.rejectsSession()) {
                    runBlocking {
                        sessionStore.clear()
                        // Кэш заказов, черновик корзины и токен пушей — тоже
                        // личные данные умершей сессии (issue #341): без
                        // уборки следующий вход на этом же устройстве видел бы
                        // их в офлайне и получал бы чужие пуши.
                        localUserDataCleaner.clear()
                    }
                    // Повторять запрос нечем, и это конец сессии: наверху
                    // человека надо увести на вход, а не оставить перед кнопкой
                    // «повторить», которой уже нечем помочь (issue #138).
                    sessionExpiry.notifyExpired()
                    // Та же причина, что и у сброса ниже: следующий вход пишет
                    // сессию мимо этого класса, и застрявший счётчик убил бы
                    // её на первом же неоднозначном ответе (issue #198).
                    reset()
                    return@synchronized null
                }
                // Refresh не дошёл до сервера. Вернуть `null` значило бы отдать
                // экрану исходный 401 с текстом «Kirish uchun autentifikatsiya
                // talab qilinadi» — ровно то, на что жаловались в issue #239, —
                // хотя сессия жива и дело в сети. Исключение уходит из
                // `authenticate` мимо повторов OkHttp и доезжает до `apiCall`
                // как «нет сети» или «таймаут»; тело 401 закрываем сами, иначе
                // соединение утечёт.
                refresh.networkFailure()?.let { cause ->
                    response.close()
                    throw cause
                }
                if (refresh.isAmbiguousContractFailure()) {
                    val now = clock.instant()
                    val previous = lastAmbiguousRefreshFailureAt
                    // Ответы почти одновременно — это один и тот же
                    // всплеск параллельных 401, а не отдельные обращения к
                    // контракту, и второй, и третий такой ответ подряд не
                    // добавляют новой информации (issue #301).
                    val isSeparateFailure = previous == null ||
                        Duration.between(previous, now) >= MIN_AMBIGUOUS_REFRESH_GAP
                    if (isSeparateFailure) {
                        consecutiveAmbiguousRefreshFailures++
                        lastAmbiguousRefreshFailureAt = now
                        if (consecutiveAmbiguousRefreshFailures >= MAX_AMBIGUOUS_REFRESH_FAILURES) {
                            // Один такой ответ — скорее подменённый ответ чужого
                            // прокси (см. `rejectsSession`), но столько подряд —
                            // уже не он: прокси на одном и том же теле не
                            // зацикливается, а сломанный контракт — да. Без этого
                            // сессия жива вечно, а сервер её токены не понимает
                            // (issue #198).
                            runBlocking {
                                sessionStore.clear()
                                localUserDataCleaner.clear()
                            }
                            sessionExpiry.notifyExpired()
                            // Сессия мертва — считать дальше нечего. Не обнулить
                            // здесь значило бы, что счётчик переживает вход
                            // заново: сессию пишет `SessionStore.save` мимо
                            // `TokenAuthenticator`, так что без явного сброса
                            // первый же неоднозначный ответ в новой сессии сразу
                            // добивает счёт до порога и стирает её мгновенно.
                            reset()
                        }
                    }
                    return@synchronized null
                }
                // Осмысленный отказ сервера (403/400/404/429/5xx) — счётчик
                // не двигаем: он не про эти причины, они уже разобраны выше.
                return@synchronized null
            }
            reset()

            runBlocking {
                sessionStore.save(
                    Session(
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        // Сервер отдаёт «через сколько истечёт», хранить
                        // полезно «когда истечёт» — иначе после перезапуска
                        // приложения значение бессмысленно. Не сообщил —
                        // срок неизвестен, а не «истёк сейчас».
                        expiresAtEpochSeconds = tokens.accessExpiresIn
                            ?.let { clock.instant().epochSecond + it }
                            ?: Session.UNKNOWN_EXPIRY,
                        sessionId = refreshed.sessionId ?: session.sessionId,
                    ),
                )
            }
            response.request.withBearer(accessToken)
        }
    }

    /**
     * Отказал ли сервер самой сессии — то есть ответил на refresh **401**.
     *
     * Только 401, и это сверено с кодом бэкенда (jack5505/mahalla,
     * `BankAuthService.refreshToken` и `JwtService.parseClaims`): каждый
     * отказ «этой сессии больше нет» там — `UnauthorizedException` с кодом
     * `TOKEN_EXPIRED`, `TOKEN_INVALID` (подпись, тип токена, refresh-токен
     * уже заменён ротацией) или `TOKEN_HIJACK` (отпечаток устройства другой,
     * сессия отозвана; хэш обнуляет и отзыв устройства). У остальных ответов
     * причина не в токене:
     *  - 403 — `GEO_*` из `geoService.requireLocation` (первая строка
     *    refresh) или блокировка аккаунта/устройства: вход заново не поможет
     *    ни там, ни там;
     *  - 400 — форма нашего запроса (`VALIDATION_ERROR` на координаты): новое
     *    обязательное поле на бэкенде разлогинило бы всех разом;
     *  - 404 — у refresh это `user.not_found`, то есть аккаунт удалён; редкий
     *    случай, а тот же код от неверного адреса сервера разлогинил бы всех;
     *  - 429, 5xx, обрыв, таймаут — «спросить не удалось»;
     *  - 2xx без токенов, `success: false` при 2xx или неразбираемое тело —
     *    подменённый ответ (вокзальный Wi-Fi) или сломанный контракт, но не
     *    отказ: бэкенд так на refresh не отвечает. Единичный такой ответ
     *    сессию не трогает; счётчик подряд идущих — [isAmbiguousContractFailure]
     *    (issue #198).
     *
     * Ошибиться в сторону «стереть» дорого: выход на экран входа стоит
     * человеку платного SMS и всей регистрации заново (issue #138).
     */
    private fun ApiResult<*>.rejectsSession(): Boolean =
        (this as? ApiResult.Failure)?.error == ApiError.Unauthorized

    /** Refresh упал в сети — исключение, которое честно назовёт это экрану. */
    private fun ApiResult<*>.networkFailure(): IOException? =
        when ((this as? ApiResult.Failure)?.error) {
            ApiError.Timeout -> SocketTimeoutException(REFRESH_FAILED)
            ApiError.NoConnection -> IOException(REFRESH_FAILED)
            else -> null
        }

    /**
     * Ответ, у которого нет причины, названной сервером: тело не разобралось,
     * `success: false` при 2xx или 2xx вовсе без пары токенов (см. разбор в
     * [rejectsSession]). Вызывается только когда `accessToken`/`refreshToken`
     * уже проверены на `null` — поэтому успешный разбор здесь и означает
     * «токенов в нём не было».
     *
     * Ровно эти три причины ломались за месяц четырежды (`data.tokens`
     * переезжал, менялась схема) — в отличие от 403/400/404/429/5xx, у
     * которых причина в самом запросе или в бэкенде, а не в контракте
     * (issue #198).
     */
    private fun ApiResult<*>.isAmbiguousContractFailure(): Boolean = when (this) {
        is ApiResult.Success<*> -> true
        is ApiResult.Failure -> error == ApiError.Serialization || error is ApiError.Business
    }

    private fun Request.withBearer(token: String): Request = newBuilder()
        .header(HEADER_AUTHORIZATION, AuthInterceptor.bearer(token))
        .build()

    /**
     * Сколько раз этот запрос уже упирался в 401. Считать всю цепочку
     * `priorResponse` нельзя: туда попадают и редиректы, так что после одного
     * 3xx лимит был бы исчерпан и refresh не случился бы вообще.
     */
    private fun attemptCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            if (prior.code == HTTP_UNAUTHORIZED) count++
            prior = prior.priorResponse
        }
        return count
    }

    companion object {
        /** Один исходный запрос + один повтор после refresh. */
        const val MAX_ATTEMPTS = 2

        /**
         * После скольких подряд идущих [isAmbiguousContractFailure] считать
         * сессию мёртвой (issue #198). Один такой ответ прощаем — это может
         * быть чужой прокси; но прокси не отвечает одно и то же на каждый
         * повторный refresh, а сломанный контракт — да, поэтому несколько
         * подряд уже не совпадение.
         *
         * «Подряд» — это без удачного refresh между ними, а не в фиксированный
         * промежуток времени: refresh случается только по 401, так что при
         * долгоживущем процессе три редких (например, раз в неделю) глюка
         * чужого прокси тоже сложатся в срабатывание. Счётчик обнуляется либо
         * удачным refresh, либо смертью сессии — на общее время жизни сессии
         * не завязан. Единственное время, которое учитывается, — расстояние
         * между *соседними* ответами, [MIN_AMBIGUOUS_REFRESH_GAP]: оно отсеивает
         * не «редкие через неделю», а «несколько почти в один момент» — ровно
         * то, что и не должно объединяться (issue #301).
         */
        const val MAX_AMBIGUOUS_REFRESH_FAILURES = 3

        /**
         * Минимальный промежуток между двумя ответами, которые засчитываются
         * как *разные* [isAmbiguousContractFailure] в [MAX_AMBIGUOUS_REFRESH_FAILURES]
         * (issue #301). Несколько экранов, упёршихся в 401 почти одновременно
         * (например, на холодном старте), по очереди берут лок и делают свой
         * refresh — если в этот момент ответ подменяет чужой прокси
         * (issue #138), он может отдать одно и то же тело на все эти близкие
         * по времени запросы. Такой всплеск укладывается в секунды: ответ на
         * `auth/refresh` не зависает, зависшие уходят через `networkFailure`
         * и счётчик не трогают. Настоящие независимые провалы контракта
         * так близко друг к другу не садятся — между ними должен пройти хотя
         * бы один обычный запрос с истёкшим токеном.
         *
         * Отсчёт идёт от предыдущего *засчитанного* ответа, а весь всплеск
         * серийный (общий `synchronized`), так что при N участниках всплеска
         * запас на каждую пару соседей — это `MIN_AMBIGUOUS_REFRESH_GAP` минус
         * время одного refresh, а не минус время всего всплеска. Больше запаса
         * (секунды, не миллисекунды) не помешает: единственная цена ошибки в
         * другую сторону — на один прощённый неоднозначный ответ дольше между
         * *настоящими* поломками контракта, а до них счётчик и так копится
         * без ограничения по времени жизни сессии. Полностью снять этот риск
         * может только вариант 2 из issue #301 (не более одного живого refresh
         * даже после провала) — отдельная переработка `synchronized`-блока, не
         * входит в эту задачу.
         */
        val MIN_AMBIGUOUS_REFRESH_GAP: Duration = Duration.ofSeconds(10)

        private const val HTTP_UNAUTHORIZED = 401

        private const val REFRESH_FAILED = "auth/refresh did not reach the server"
    }
}
