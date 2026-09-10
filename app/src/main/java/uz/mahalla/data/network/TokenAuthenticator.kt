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
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Clock
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
) : Authenticator {

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
                    runBlocking { sessionStore.clear() }
                    // Повторять запрос нечем, и это конец сессии: наверху
                    // человека надо увести на вход, а не оставить перед кнопкой
                    // «повторить», которой уже нечем помочь (issue #138).
                    sessionExpiry.notifyExpired()
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
                return@synchronized null
            }

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
     *    отказ: бэкенд так на refresh не отвечает.
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

        private const val HTTP_UNAUTHORIZED = 401

        private const val REFRESH_FAILED = "auth/refresh did not reach the server"
    }
}
