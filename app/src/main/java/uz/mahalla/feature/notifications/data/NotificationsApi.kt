package uz.mahalla.feature.notifications.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Query
import uz.mahalla.data.network.ApiResponse

/**
 * Центр уведомлений (контроллер `notification`, issue #81).
 *
 * Контракт снят со стенда (`/v3/api-docs` + curl): все три ручки требуют
 * Bearer — без токена приходит `401 UNAUTHORIZED`, — поэтому API создаётся на
 * **основном** Retrofit, а не на «голом» `@RefreshClient`. Гео-заголовки тоже
 * обязательны (без них `403 GEO_PERMISSION_REQUIRED`), но их ставит
 * `GeoHeaderInterceptor` на обоих клиентах (issue #53).
 *
 * Отметки **отдельного** уведомления прочитанным у бэкенда нет: в контроллере
 * ровно эти три операции. Поэтому «прочитано» в приложении меняется только
 * целиком, кнопкой «прочитать всё».
 */
interface NotificationsApi {

    @GET("notifications")
    suspend fun notifications(
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ApiResponse<NotificationPageDto>

    /** `ApiResponseLong`: непрочитанных столько-то. */
    @GET("notifications/unread-count")
    suspend fun unreadCount(): ApiResponse<Long>

    /** Ответ — конверт без полезной нагрузки: `data` пуст и при успехе. */
    @PUT("notifications/read-all")
    suspend fun markAllRead(): ApiResponse<JsonElement>
}

/** `PageResponseNotification` — пагинация у уведомлений настоящая. */
@Serializable
data class NotificationPageDto(
    @SerialName("content") val content: List<NotificationDto> = emptyList(),
    @SerialName("page") val page: Int? = null,
    @SerialName("size") val size: Int? = null,
    @SerialName("totalElements") val totalElements: Long? = null,
    @SerialName("totalPages") val totalPages: Int? = null,
    @SerialName("first") val first: Boolean? = null,
    @SerialName("last") val last: Boolean? = null,
)

/**
 * `Notification`. Все поля необязательные: отсутствие любого из них — не повод
 * показать экран ошибки вместо списка.
 *
 * `isRead` принимается и под именем `read`: Jackson сериализует
 * `Boolean isRead` то так, то так, в зависимости от геттера, а ошибка здесь
 * покрасила бы весь список непрочитанным.
 */
@Serializable
data class NotificationDto(
    @SerialName("id") val id: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("body") val body: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("entityId") val entityId: String? = null,
    @SerialName("isRead") val isRead: Boolean? = null,
    @SerialName("read") val read: Boolean? = null,
    @SerialName("imageUrl") val imageUrl: String? = null,
    @SerialName("createdAt") val createdAt: String? = null,
)
