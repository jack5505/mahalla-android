package uz.mahalla.feature.social.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import uz.mahalla.data.network.ApiResponse
import uz.mahalla.feature.discovery.data.PageDto

/**
 * Лайк, «Избранное» и комментарии (контроллер `social`, issue #75).
 *
 * Контракт снят со стенда (`https://189-74-96-232.nip.io/v3/api-docs` + curl):
 * все семь ручек требуют Bearer — без токена приходит `401 UNAUTHORIZED`, —
 * поэтому API создаётся на **основном** Retrofit, а не на «голом»
 * `@RefreshClient`. Ответы приезжают в общем конверте `{success, data, error}`.
 *
 * Отдельного `DELETE` у лайка и сохранения нет: обе ручки — переключатели,
 * и новое состояние приезжает в ответе.
 */
interface SocialApi {

    @GET("places/{placeId}/status")
    suspend fun status(@Path("placeId") placeId: String): ApiResponse<PlaceStatusDto>

    @POST("places/{placeId}/like")
    suspend fun like(@Path("placeId") placeId: String): ApiResponse<LikeDto>

    /** `ApiResponseBoolean`: в `data` лежит новое состояние «в избранном». */
    @POST("places/{placeId}/save")
    suspend fun save(@Path("placeId") placeId: String): ApiResponse<Boolean>

    @GET("places/{placeId}/comments")
    suspend fun comments(
        @Path("placeId") placeId: String,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = DEFAULT_PAGE_SIZE,
    ): ApiResponse<PageDto<CommentDto>>

    @POST("places/{placeId}/comments")
    suspend fun addComment(
        @Path("placeId") placeId: String,
        @Body body: AddCommentRequest,
    ): ApiResponse<CommentDto>

    /** Ответ — конверт без полезной нагрузки: `data` пуст и при успехе. */
    @DELETE("comments/{id}")
    suspend fun deleteComment(@Path("id") commentId: String): ApiResponse<JsonElement>

    /**
     * «Избранное» — **только идентификаторы** (`PageResponseUUID`), без
     * карточек мест. Собирать список приходится N+1 запросом, см.
     * [DefaultSocialRepository.savedPlaces].
     */
    @GET("saved-places")
    suspend fun savedPlaces(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = DEFAULT_PAGE_SIZE,
    ): ApiResponse<PageDto<String>>

    companion object {
        /** Столько же по умолчанию берёт и сам бэкенд. */
        const val DEFAULT_PAGE_SIZE = 20
    }
}

/** `PlaceStatusResponse`. Поля необязательны: молчание — это «нет и ноль». */
@Serializable
data class PlaceStatusDto(
    @SerialName("liked") val liked: Boolean = false,
    @SerialName("saved") val saved: Boolean = false,
    @SerialName("totalLikes") val totalLikes: Long = 0,
)

/** `LikeResponse` — «сохранено» здесь не приезжает, его хранит экран. */
@Serializable
data class LikeDto(
    @SerialName("liked") val liked: Boolean = false,
    @SerialName("totalLikes") val totalLikes: Long? = null,
)

/**
 * `CommentResponse`. Имени автора в контракте нет — только `userId`; подпись
 * под чужим комментарием поэтому общая (см. `PlaceComment`).
 */
@Serializable
data class CommentDto(
    @SerialName("id") val id: String? = null,
    @SerialName("userId") val userId: String? = null,
    @SerialName("placeId") val placeId: String? = null,
    @SerialName("text") val text: String = "",
    @SerialName("createdAt") val createdAt: String? = null,
)

/**
 * Тело нового комментария. В схеме оно объявлено как `Map<String,String>` без
 * перечня ключей, поэтому имя поля взято из ответа (`CommentResponse.text`):
 * другого источника у контракта нет.
 */
@Serializable
data class AddCommentRequest(
    @SerialName("text") val text: String,
)
