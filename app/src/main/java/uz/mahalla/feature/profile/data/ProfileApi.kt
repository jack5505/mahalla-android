package uz.mahalla.feature.profile.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import uz.mahalla.data.network.ApiResponse

/**
 * Профиль на сервере (issue #170). Контракт снят чтением `/v3/api-docs`
 * 2026-09-10 (issue #237), лежит в `docs/API-CONTRACT.md`.
 *
 * `language`, `telegramLinked` и `lastLoginAt` бэкенд отдаёт, но приложение их
 * не разбирает: ни хранить, ни показать их пока негде (`language` — issue
 * #242, роль и остальное — `docs/adr/0007-yazyk-i-rol-istochnik-istiny.md`).
 */
@Serializable
data class MeResponseDto(
    @SerialName("id") val id: String? = null,
    @SerialName("phone") val phone: String? = null,
    @SerialName("fullName") val fullName: String? = null,
    @SerialName("avatarUrl") val avatarUrl: String? = null,
    @SerialName("role") val role: String? = null,
    @SerialName("verificationStatus") val verificationStatus: String? = null,
    @SerialName("accountStatus") val accountStatus: String? = null,
)

/**
 * `PUT` — PATCH по смыслу (`docs/API-CONTRACT.md`): поля нет или `null` —
 * значение не меняется, пустая строка — снимается (`avatarUrl = ""` снимает
 * фото). Отправлять оба поля разом не обязательно.
 */
@Serializable
data class UpdateMeRequest(
    @SerialName("fullName") val fullName: String? = null,
    @SerialName("avatarUrl") val avatarUrl: String? = null,
)

/**
 * `users/me` (issue #170). Запросы авторизованные — без токена 401, как и у
 * [uz.mahalla.feature.profile.data.SessionsApi], поэтому API создаётся на
 * **основном** клиенте, с `AuthInterceptor` и `TokenAuthenticator`.
 */
interface ProfileApi {

    @GET("users/me")
    suspend fun getMe(): ApiResponse<MeResponseDto>

    @PUT("users/me")
    suspend fun updateMe(@Body body: UpdateMeRequest): ApiResponse<MeResponseDto>
}
