package uz.mahalla.feature.role.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import uz.mahalla.data.network.ApiResponse

/**
 * Сотрудники заведения (`place-staff-controller`, issue #189). Контракт снят
 * со стенда (`/v3/api-docs`, 2026-09-11): все четыре ручки, схемы
 * `PlaceStaffResponse`, `AddRequest`, `PlaceStaffChangeRoleRequest` встречаются
 * в схеме по одному разу, коллизии springdoc здесь нет.
 *
 * Как и у остальных ручек `places` в [ProviderApi], доступ владельца
 * проверяется бэкендом, а не клиентом; ходить сюда без токена или не будучи
 * владельцем — на `401`/`403`, которые `apiCall` превращает в обычный
 * [uz.mahalla.core.result.ApiError].
 */
interface PlaceStaffApi {

    @GET("places/{placeId}/staff")
    suspend fun list(@Path("placeId") placeId: String): ApiResponse<List<PlaceStaffDto>>

    @POST("places/{placeId}/staff")
    suspend fun add(
        @Path("placeId") placeId: String,
        @Body body: AddPlaceStaffRequest,
    ): ApiResponse<PlaceStaffDto>

    @PUT("places/{placeId}/staff/{staffUserId}")
    suspend fun changeRole(
        @Path("placeId") placeId: String,
        @Path("staffUserId") staffUserId: String,
        @Body body: ChangePlaceStaffRoleRequest,
    ): ApiResponse<PlaceStaffDto>

    /** Ответ — `ApiResponseVoid`: `data` пуст и при успехе, как у отзыва сессии. */
    @DELETE("places/{placeId}/staff/{staffUserId}")
    suspend fun remove(
        @Path("placeId") placeId: String,
        @Path("staffUserId") staffUserId: String,
    ): ApiResponse<JsonElement>
}

/** `PlaceStaffResponse`. `id` записи в домен не идёт — см. [uz.mahalla.feature.role.domain.PlaceStaffMember]. */
@Serializable
data class PlaceStaffDto(
    @SerialName("id") val id: String? = null,
    @SerialName("placeId") val placeId: String? = null,
    @SerialName("userId") val userId: String? = null,
    @SerialName("role") val role: String? = null,
    @SerialName("geoExempt") val geoExempt: Boolean? = null,
    @SerialName("createdAt") val createdAt: String? = null,
)

/** `AddRequest`: `userId` и `role` обязательны по схеме. */
@Serializable
data class AddPlaceStaffRequest(
    @SerialName("userId") val userId: String,
    @SerialName("role") val role: String,
)

/** `PlaceStaffChangeRoleRequest`. */
@Serializable
data class ChangePlaceStaffRoleRequest(
    @SerialName("role") val role: String,
)
