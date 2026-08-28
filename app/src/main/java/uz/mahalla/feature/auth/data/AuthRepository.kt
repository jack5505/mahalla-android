package uz.mahalla.feature.auth.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.data.device.DeviceInfoProvider
import uz.mahalla.data.location.RequestLocationProvider
import uz.mahalla.data.network.auth.AuthApi
import uz.mahalla.data.network.auth.RefreshTokenRequest
import uz.mahalla.data.network.auth.SendOtpRequest
import uz.mahalla.data.network.auth.TokenPairDto
import uz.mahalla.data.network.auth.VerifyOtpRequest
import uz.mahalla.data.network.auth.toDto
import uz.mahalla.data.network.payload
import uz.mahalla.data.prefs.Session
import uz.mahalla.data.prefs.SessionStore
import uz.mahalla.data.security.PinStorage
import uz.mahalla.feature.auth.domain.LoginResult
import uz.mahalla.feature.auth.domain.OtpChallenge
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Авторизация по SMS-коду (эпик 3, сквозная задача).
 *
 * Интерфейс, а не класс: ViewModel'и онбординга тестируются с фейком, без
 * MockWebServer и без графа DI.
 */
interface AuthRepository {

    /** Есть ли сохранённая сессия. Источник — [SessionStore], а не память. */
    val isAuthorized: Flow<Boolean>

    suspend fun requestCode(phoneE164: String): ApiResult<OtpChallenge>

    /**
     * Успех сохраняет сессию — вызывающему остаётся только навигация.
     *
     * Код проверяется по [otpToken] из [requestCode], а не по номеру телефона:
     * так решил бэкенд (issue #42), и это заодно означает, что просроченный
     * или чужой токен отсекается сервером, а не сравнением строк на клиенте.
     */
    suspend fun verifyCode(otpToken: String, code: String): ApiResult<LoginResult>

    /**
     * Явное обновление токенов. Обычный путь — `TokenAuthenticator` по 401;
     * этот метод нужен там, где сессию надо проверить до запроса (запуск
     * приложения, разблокировка по PIN).
     */
    suspend fun refresh(): ApiResult<Unit>

    /** Локальные данные чистятся всегда, даже если запрос к серверу не ушёл. */
    suspend fun logout()
}

@Singleton
class DefaultAuthRepository @Inject constructor(
    private val authApi: AuthApi,
    private val sessionStore: SessionStore,
    private val pinStorage: PinStorage,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val locationProvider: RequestLocationProvider,
    private val clock: Clock,
) : AuthRepository {

    override val isAuthorized: Flow<Boolean> = sessionStore.session.map { it != null }

    override suspend fun requestCode(phoneE164: String): ApiResult<OtpChallenge> {
        val device = deviceInfoProvider.current().toDto()
        val location = locationProvider.current()

        val result = apiCall {
            authApi.sendOtp(
                SendOtpRequest(
                    phone = phoneE164,
                    device = device,
                    lat = location.latitude,
                    lng = location.longitude,
                ),
            ).payload()
        }

        return when (result) {
            is ApiResult.Success -> {
                val otpToken = result.data.otpToken
                // Без токена проверить код нечем: уходить на экран ввода
                // означало бы гарантированную ошибку после шестой цифры.
                if (otpToken.isNullOrBlank()) {
                    ApiResult.Failure(ApiError.Serialization)
                } else {
                    ApiResult.Success(
                        OtpChallenge.of(
                            otpToken = otpToken,
                            codeLength = null,
                            resendAfterSeconds = result.data.cooldownSeconds,
                            expiresInSeconds = result.data.expiresInSeconds,
                        ),
                    )
                }
            }

            is ApiResult.Failure -> result
        }
    }

    override suspend fun verifyCode(otpToken: String, code: String): ApiResult<LoginResult> {
        val device = deviceInfoProvider.current().toDto()
        val location = locationProvider.current()

        val result = apiCall {
            authApi.verifyOtp(
                VerifyOtpRequest(
                    otpToken = otpToken,
                    otpCode = code,
                    device = device,
                    lat = location.latitude,
                    lng = location.longitude,
                ),
            ).payload()
        }

        return when (result) {
            is ApiResult.Success -> {
                val session = result.data.tokens.toSession(sessionId = result.data.sessionId)
                    ?: return ApiResult.Failure(ApiError.Serialization)
                sessionStore.save(session)
                ApiResult.Success(
                    LoginResult(isNewUser = result.data.user?.fullName.isNullOrBlank()),
                )
            }

            is ApiResult.Failure -> result
        }
    }

    override suspend fun refresh(): ApiResult<Unit> {
        val session = sessionStore.current() ?: return ApiResult.Failure(ApiError.Unauthorized)
        val device = deviceInfoProvider.current().toDto()
        val location = locationProvider.current()

        val result = apiCall {
            authApi.refresh(
                RefreshTokenRequest(
                    refreshToken = session.refreshToken,
                    device = device,
                    lat = location.latitude,
                    lng = location.longitude,
                ),
            ).payload()
        }

        return when (result) {
            is ApiResult.Success -> {
                val refreshed = result.data.tokens
                    .toSession(sessionId = result.data.sessionId ?: session.sessionId)
                    ?: return ApiResult.Failure(ApiError.Serialization)
                sessionStore.save(refreshed)
                ApiResult.Success(Unit)
            }

            is ApiResult.Failure -> {
                // Refresh-токен мёртв — сессии больше нет, иначе приложение
                // будет вечно ходить с невалидной парой токенов.
                if (result.error == ApiError.Unauthorized) sessionStore.clear()
                result
            }
        }
    }

    override suspend fun logout() {
        val session = sessionStore.current()
        // Ответ сервера не важен: локальный выход должен случиться и без сети,
        // иначе пользователь останется «залогиненным» на устройстве.
        if (session != null) {
            apiCall { authApi.logout(sessionId = session.sessionId, allDevices = false) }
        }
        sessionStore.clear()
        // PIN защищает именно эту сессию — оставлять его от прошлого
        // пользователя нельзя.
        pinStorage.clear()
    }

    /**
     * Сервер сообщает «через сколько истечёт», хранить полезно «когда
     * истечёт»: после перезапуска приложения относительное значение
     * бессмысленно. Не сообщил — срок неизвестен, а не «истёк в 1970».
     *
     * `null` означает ответ без пары токенов: такой успех для клиента
     * бесполезен, вызывающий превращает его в ошибку.
     */
    private fun TokenPairDto?.toSession(sessionId: String?): Session? {
        val access = this?.accessToken?.takeIf { it.isNotBlank() } ?: return null
        val refresh = this.refreshToken?.takeIf { it.isNotBlank() } ?: return null
        return Session(
            accessToken = access,
            refreshToken = refresh,
            expiresAtEpochSeconds = accessExpiresIn
                ?.let { clock.instant().epochSecond + it }
                ?: Session.UNKNOWN_EXPIRY,
            sessionId = sessionId,
        )
    }
}
