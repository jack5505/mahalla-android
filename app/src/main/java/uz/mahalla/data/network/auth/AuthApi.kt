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

/**
 * Обновление токенов. Ходит через отдельный клиент (`@RefreshClient`), без
 * авторизационного интерсептора и без `Authenticator`.
 */
interface AuthApi {
    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshTokenRequest): TokenPairResponse
}
