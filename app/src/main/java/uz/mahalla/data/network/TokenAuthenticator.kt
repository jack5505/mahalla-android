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
import uz.mahalla.data.prefs.SessionExpiry
import uz.mahalla.data.prefs.SessionStore
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
 * стирается не при любом провале refresh, а только когда сервер ответил и
 * отказал — см. `rejectsSession` (issue #138).
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
                // Устройство и координаты бэкенд требует и здесь: refresh для
                // него — это продление сессии конкретного устройства.
                val device = deviceInfoProvider.current().toDto()
                val location = locationProvider.current()
                apiCall {
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
     * Отказал ли сервер самой сессии.
     *
     * Стирать токены можно только когда он ответил и отказал по существу:
     * 401/403, `success: false` в конверте, «запрос неверен» ([BAD_REQUEST],
     * [UNPROCESSABLE]) или успешный ответ без токенов. Всё остальное — это
     * «спросить не удалось», и сессия остаётся: обрыв связи, таймаут, 5xx,
     * 404 у ручки и, отдельно, **429** — троттлинг говорит «зайдите позже», а
     * не «токен мёртв». Ошибиться здесь дорого: выход на экран входа стоит
     * человеку платного SMS и всей регистрации заново (issue #138).
     *
     * Битое тело (`Serialization`) сессию тоже не заканчивает: 200 с
     * неразбираемым содержимым — это чаще подменённый ответ вокзального
     * Wi-Fi, чем отказ бэкенда.
     */
    private fun ApiResult<*>.rejectsSession(): Boolean = when (this) {
        // Ответ пришёл, а токенов в нём нет: продлевать сессию сервер не стал.
        is ApiResult.Success -> true

        is ApiResult.Failure -> when (val apiError = error) {
            ApiError.Unauthorized, ApiError.Forbidden, is ApiError.Business -> true
            is ApiError.Http -> apiError.code == BAD_REQUEST || apiError.code == UNPROCESSABLE
            else -> false
        }
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

        /** «Запрос неверен» — то есть неверен наш refresh-токен. */
        private const val BAD_REQUEST = 400
        private const val UNPROCESSABLE = 422
    }
}
