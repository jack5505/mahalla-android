package uz.mahalla.feature.auth.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.data.network.auth.AuthApi
import uz.mahalla.data.network.auth.LoginResponse
import uz.mahalla.data.network.auth.OtpRequest
import uz.mahalla.data.network.auth.OtpVerifyRequest
import uz.mahalla.data.network.auth.RefreshTokenRequest
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

    /** Успех сохраняет сессию — вызывающему остаётся только навигация. */
    suspend fun verifyCode(phoneE164: String, code: String): ApiResult<LoginResult>

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
    private val clock: Clock,
) : AuthRepository {

    override val isAuthorized: Flow<Boolean> = sessionStore.session.map { it != null }

    override suspend fun requestCode(phoneE164: String): ApiResult<OtpChallenge> {
        val result = apiCall { authApi.requestOtp(OtpRequest(phoneE164)) }
        return when (result) {
            is ApiResult.Success -> ApiResult.Success(
                OtpChallenge.of(
                    codeLength = result.data.codeLength,
                    resendAfterSeconds = result.data.resendAfterSeconds,
                    expiresInSeconds = result.data.expiresInSeconds,
                ),
            )

            is ApiResult.Failure -> result
        }
    }

    override suspend fun verifyCode(phoneE164: String, code: String): ApiResult<LoginResult> {
        val result = apiCall { authApi.verifyOtp(OtpVerifyRequest(phoneE164, code)) }
        return when (result) {
            is ApiResult.Success -> {
                sessionStore.save(result.data.toSession())
                ApiResult.Success(LoginResult(isNewUser = result.data.isNewUser))
            }

            is ApiResult.Failure -> result
        }
    }

    override suspend fun refresh(): ApiResult<Unit> {
        val session = sessionStore.current() ?: return ApiResult.Failure(ApiError.Unauthorized)
        val result = apiCall { authApi.refresh(RefreshTokenRequest(session.refreshToken)) }
        return when (result) {
            is ApiResult.Success -> {
                sessionStore.save(
                    session(
                        accessToken = result.data.accessToken,
                        refreshToken = result.data.refreshToken,
                        expiresInSeconds = result.data.expiresInSeconds,
                    ),
                )
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
        val refreshToken = sessionStore.current()?.refreshToken
        // Ответ сервера не важен: локальный выход должен случиться и без сети,
        // иначе пользователь останется «залогиненным» на устройстве.
        if (refreshToken != null) apiCall { authApi.logout(RefreshTokenRequest(refreshToken)) }
        sessionStore.clear()
        // PIN защищает именно эту сессию — оставлять его от прошлого
        // пользователя нельзя.
        pinStorage.clear()
    }

    private fun LoginResponse.toSession(): Session = session(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresInSeconds = expiresInSeconds,
    )

    /**
     * Сервер сообщает «через сколько истечёт», хранить полезно «когда
     * истечёт»: после перезапуска приложения относительное значение
     * бессмысленно. Не сообщил — срок неизвестен, а не «истёк в 1970».
     */
    private fun session(
        accessToken: String,
        refreshToken: String,
        expiresInSeconds: Long?,
    ): Session = Session(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAtEpochSeconds = expiresInSeconds
            ?.let { clock.instant().epochSecond + it }
            ?: Session.UNKNOWN_EXPIRY,
    )
}
