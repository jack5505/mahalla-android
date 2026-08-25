package uz.mahalla.data.network.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

@Serializable
data class RefreshTokenRequest(
    @SerialName("refreshToken") val refreshToken: String,
)

@Serializable
data class TokenPairResponse(
    @SerialName("accessToken") val accessToken: String,
    @SerialName("refreshToken") val refreshToken: String,
    @SerialName("expiresIn") val expiresInSeconds: Long = 0L,
)

/**
 * Обновление токенов. Ходит через отдельный клиент (`@RefreshClient`), без
 * авторизационного интерсептора и без `Authenticator`.
 */
interface AuthApi {
    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshTokenRequest): TokenPairResponse
}
