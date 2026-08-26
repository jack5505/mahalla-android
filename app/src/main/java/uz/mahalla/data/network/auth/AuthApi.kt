package uz.mahalla.data.network.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

@Serializable
data class RefreshTokenRequest(
    @SerialName("refreshToken") val refreshToken: String,
)

/**
 * @param expiresInSeconds `null`, если сервер не сообщил срок жизни. Именно
 * `null`, а не `0`: ноль означал бы «токен уже истёк», и клиент начал бы
 * обновлять свежий токен на каждом запросе.
 */
@Serializable
data class TokenPairResponse(
    @SerialName("accessToken") val accessToken: String,
    @SerialName("refreshToken") val refreshToken: String,
    @SerialName("expiresIn") val expiresInSeconds: Long? = null,
)

@Serializable
data class OtpRequest(
    @SerialName("phone") val phone: String,
)

/**
 * Ответ на запрос кода (эпик 3.3). Все поля необязательные: пока бэкенд не
 * зафиксировал контракт, клиент подставляет свои значения по умолчанию
 * (см. `OtpChallenge`), а не падает.
 *
 * @param resendAfterSeconds через сколько можно просить код повторно.
 * @param expiresInSeconds сколько живёт сам код.
 */
@Serializable
data class OtpChallengeResponse(
    @SerialName("resendAfter") val resendAfterSeconds: Int? = null,
    @SerialName("expiresIn") val expiresInSeconds: Int? = null,
    @SerialName("codeLength") val codeLength: Int? = null,
)

@Serializable
data class OtpVerifyRequest(
    @SerialName("phone") val phone: String,
    @SerialName("code") val code: String,
)

/**
 * Успешная верификация кода: та же пара токенов плюс признак нового
 * пользователя (нужен, чтобы после онбординга показать или не показывать
 * заполнение профиля).
 */
@Serializable
data class LoginResponse(
    @SerialName("accessToken") val accessToken: String,
    @SerialName("refreshToken") val refreshToken: String,
    @SerialName("expiresIn") val expiresInSeconds: Long? = null,
    @SerialName("isNewUser") val isNewUser: Boolean = false,
)

/**
 * Авторизация по SMS-коду и обновление токенов.
 *
 * Все методы ходят через отдельный клиент (`@RefreshClient`) — без
 * авторизационного интерсептора и без `Authenticator`:
 *  - запрос и проверка кода анонимны по определению;
 *  - `refresh` иначе получил бы рекурсию (401 на refresh → снова refresh);
 *  - `logout` должен уходить и с уже мёртвым access-токеном, а 401 на нём не
 *    должен запускать обновление.
 */
interface AuthApi {
    @POST("auth/otp/request")
    suspend fun requestOtp(@Body body: OtpRequest): OtpChallengeResponse

    @POST("auth/otp/verify")
    suspend fun verifyOtp(@Body body: OtpVerifyRequest): LoginResponse

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshTokenRequest): TokenPairResponse

    @POST("auth/logout")
    suspend fun logout(@Body body: RefreshTokenRequest)
}
