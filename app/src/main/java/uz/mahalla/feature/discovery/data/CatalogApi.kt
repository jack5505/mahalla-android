package uz.mahalla.feature.discovery.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import uz.mahalla.data.network.ApiResponse

/**
 * Место в краткой выдаче (`PlaceSummary` в схеме бэкенда).
 *
 * Имена полей сняты с `https://189-74-96-232.nip.io/v3/api-docs` (issue #53):
 * рейтинг это `ratingAvg`/`ratingCount`, картинка — `logoUrl`, координаты —
 * `lat`/`lng`, а «работает сейчас» — `isAvailable`.
 */
@Serializable
data class PlaceSummaryDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String = "",
    @SerialName("category") val category: String = "",
    @SerialName("address") val address: String? = null,
    @SerialName("lat") val latitude: Double? = null,
    @SerialName("lng") val longitude: Double? = null,
    @SerialName("isAvailable") val isAvailable: Boolean = false,
    @SerialName("ratingAvg") val ratingAvg: Double = 0.0,
    @SerialName("ratingCount") val ratingCount: Int = 0,
    /** Считает сервер — у него координаты из запроса. `null` в ответе поиска. */
    @SerialName("distanceMeters") val distanceMeters: Double? = null,
    @SerialName("logoUrl") val logoUrl: String? = null,
)

/** Карточка места (`PlaceDetail`). Расписания бэкенд пока не отдаёт. */
@Serializable
data class PlaceDetailDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String = "",
    @SerialName("category") val category: String = "",
    @SerialName("description") val description: String? = null,
    @SerialName("address") val address: String? = null,
    @SerialName("lat") val latitude: Double? = null,
    @SerialName("lng") val longitude: Double? = null,
    @SerialName("city") val city: String? = null,
    @SerialName("phone") val phone: String? = null,
    @SerialName("website") val website: String? = null,
    @SerialName("isAvailable") val isAvailable: Boolean = false,
    @SerialName("ratingAvg") val ratingAvg: Double = 0.0,
    @SerialName("ratingCount") val ratingCount: Int = 0,
    @SerialName("logoUrl") val logoUrl: String? = null,
    @SerialName("coverUrl") val coverUrl: String? = null,
)

/**
 * Документ поискового индекса (`PlaceDocument`) — ответ `GET /search`.
 *
 * Полей меньше, чем в выдаче «рядом»: ни адреса, ни числа отзывов, ни
 * расстояния. Расстояние считаем сами по координатам (`GeoDistance`) — иначе
 * найденное поиском место показывало бы «0 м».
 */
@Serializable
data class PlaceDocumentDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String = "",
    @SerialName("category") val category: String = "",
    @SerialName("description") val description: String? = null,
    @SerialName("city") val city: String? = null,
    @SerialName("lat") val latitude: Double? = null,
    @SerialName("lng") val longitude: Double? = null,
    @SerialName("ratingAvg") val ratingAvg: Double = 0.0,
    @SerialName("isActive") val isActive: Boolean = true,
)

/**
 * Отзыв. Имена полей в схеме стенда перекрыты коллизией `Response` (springdoc
 * склеил несколько классов с одинаковым простым именем), поэтому у автора и
 * текста приняты оба вероятных варианта: разбор не должен зависеть от того,
 * какое из них окажется настоящим.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ReviewDto(
    @SerialName("id") val id: String,
    @JsonNames("author", "authorName") @SerialName("userName") val author: String = "",
    @SerialName("rating") val rating: Int = 0,
    @JsonNames("comment") @SerialName("text") val text: String = "",
    @SerialName("createdAt") val createdAt: String? = null,
    /** По той же причине — два вероятных имени поля с аватаром (issue #60). */
    @JsonNames("avatarUrl", "userAvatar") @SerialName("userAvatarUrl")
    val avatarUrl: String? = null,
)

/** Страница бэкенда: `content` + метаданные (`PageResponse`). */
@Serializable
data class PageDto<T>(
    @SerialName("content") val content: List<T> = emptyList(),
    @SerialName("page") val page: Int = 0,
    @SerialName("totalPages") val totalPages: Int = 1,
    @SerialName("totalElements") val totalElements: Long = 0,
    @SerialName("last") val last: Boolean = true,
)

/**
 * Каталог мест (эпик 4), контракт снят со схемы стенда (issue #53).
 *
 * Прежний `GET places?q=…&page=…` в бэкенде не существует вовсе: у
 * `/api/v1/places` объявлен только `POST` (создание заведения), а выдача живёт
 * в трёх разных эндпоинтах. Отсюда и 403 на каждый запрос главной — до
 * маршрутизации его отклонял гео-фильтр, см. `GeoHeaderInterceptor`.
 *
 * Ответы приходят в конверте `{success, data, error}` — разворачивает
 * `ApiResponse.payload()`.
 */
interface CatalogApi {

    /**
     * Выдача «рядом». Пагинации у бэкенда нет: он отдаёт всё, что попало в
     * радиус, одним списком.
     */
    @GET("places/nearby")
    suspend fun nearby(
        @Query("lat") latitude: Double,
        @Query("lng") longitude: Double,
        @Query("radiusMeters") radiusMeters: Int = DEFAULT_RADIUS_METERS,
        @Query("category") category: String? = null,
    ): ApiResponse<List<PlaceSummaryDto>>

    /** Поиск по индексу: описание, город и название, а не только имя. */
    @GET("search")
    suspend fun search(
        @Query("query") query: String?,
        @Query("category") category: String? = null,
    ): ApiResponse<List<PlaceDocumentDto>>

    @GET("places/{id}")
    suspend fun place(@Path("id") id: String): ApiResponse<PlaceDetailDto>

    @GET("reviews/places/{placeId}")
    suspend fun reviews(
        @Path("placeId") placeId: String,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = DEFAULT_REVIEWS_SIZE,
    ): ApiResponse<PageDto<ReviewDto>>

    companion object {
        /**
         * У бэкенда по умолчанию 3 км. Для главной этого мало: в райцентре
         * список оказался бы пустым при живом каталоге через дорогу.
         */
        const val DEFAULT_RADIUS_METERS = 10_000

        const val DEFAULT_REVIEWS_SIZE = 20
    }
}
