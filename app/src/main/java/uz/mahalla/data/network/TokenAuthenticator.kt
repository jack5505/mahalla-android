package uz.mahalla.data.network

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import uz.mahalla.data.network.AuthInterceptor.Companion.BEARER_PREFIX
import uz.mahalla.data.network.AuthInterceptor.Companion.HEADER_AUTHORIZATION
import uz.mahalla.data.device.DeviceInfoProvider
import uz.mahalla.data.location.RequestLocationProvider
import uz.mahalla.data.network.auth.AuthApi
import uz.mahalla.data.network.auth.RefreshTokenRequest
import uz.mahalla.data.network.auth.toDto
import uz.mahalla.data.prefs.Session
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
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val sessionStore: SessionStore,
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

            val refreshed = runBlocking {
                // Устройство и координаты бэкенд требует и здесь: refresh для
                // него — это продление сессии конкретного устройства.
                val device = deviceInfoProvider.current().toDto()
                val location = locationProvider.current()
                runCatching {
                    authApi.refresh(
                        RefreshTokenRequest(
                            refreshToken = session.refreshToken,
                            device = device,
                            lat = location.latitude,
                            lng = location.longitude,
                        ),
                    ).payload()
                }.getOrNull()
            }

            val tokens = refreshed?.tokens
            val accessToken = tokens?.accessToken?.takeIf { it.isNotBlank() }
            val refreshToken = tokens?.refreshToken?.takeIf { it.isNotBlank() }
            if (accessToken == null || refreshToken == null) {
                runBlocking { sessionStore.clear() }
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
    }
}
