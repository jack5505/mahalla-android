package uz.mahalla.feature.discovery.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Место в ответе каталога.
 *
 * Один DTO на список и на карточку: краткая выдача просто не присылает
 * детальные поля, и все они nullable. Две почти одинаковые структуры разошлись
 * бы по именам полей при первом же изменении контракта.
 */
@Serializable
data class PlaceDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("category") val category: String = "",
    @SerialName("rating") val rating: Double = 0.0,
    @SerialName("distanceMeters") val distanceMeters: Int = 0,
    @SerialName("isOpenNow") val isOpenNow: Boolean = false,
    @SerialName("reviewCount") val reviewCount: Int = 0,
    @SerialName("address") val address: String? = null,
    @SerialName("photoUrl") val photoUrl: String? = null,
    @SerialName("latitude") val latitude: Double? = null,
    @SerialName("longitude") val longitude: Double? = null,
    @SerialName("isRecommended") val isRecommended: Boolean = false,
    // --- только в карточке ---
    @SerialName("description") val description: String? = null,
    @SerialName("photos") val photos: List<String> = emptyList(),
    @SerialName("phone") val phone: String? = null,
    @SerialName("website") val website: String? = null,
    @SerialName("openingHours") val openingHours: List<OpeningHoursDto> = emptyList(),
    @SerialName("hasQueue") val hasQueue: Boolean = false,
    @SerialName("hasBooking") val hasBooking: Boolean = false,
    @SerialName("hasOrdering") val hasOrdering: Boolean = false,
)

/**
 * `dayOfWeek` — 1..7 по ISO (понедельник = 1), время в `HH:mm`.
 * `null` в любом из полей означает выходной.
 */
@Serializable
data class OpeningHoursDto(
    @SerialName("dayOfWeek") val dayOfWeek: Int,
    @SerialName("opensAt") val opensAt: String? = null,
    @SerialName("closesAt") val closesAt: String? = null,
)

@Serializable
data class ReviewDto(
    @SerialName("id") val id: String,
    @SerialName("author") val author: String = "",
    @SerialName("rating") val rating: Int = 0,
    @SerialName("text") val text: String = "",
    @SerialName("createdAt") val createdAt: String? = null,
)

/**
 * Страница выдачи. `hasMore` считаем сами по [totalPages] — сервер отдаёт
 * привычный для Spring формат, и полагаться на «пришло меньше, чем просили»
 * нельзя: последняя страница бывает ровно полной.
 */
@Serializable
data class PlacePageDto(
    @SerialName("items") val items: List<PlaceDto> = emptyList(),
    @SerialName("page") val page: Int = 0,
    @SerialName("totalPages") val totalPages: Int = 1,
    @SerialName("totalElements") val totalElements: Int = 0,
)

@Serializable
data class ReviewPageDto(
    @SerialName("items") val items: List<ReviewDto> = emptyList(),
    @SerialName("page") val page: Int = 0,
    @SerialName("totalPages") val totalPages: Int = 1,
)

/** Каталог мест: выдача с фильтрами (4.1/4.3), карточка и отзывы (4.4). */
interface CatalogApi {

    /**
     * `null` в параметре — Retrofit просто не добавит его в URL, то есть
     * «без фильтра».
     */
    @GET("places")
    suspend fun places(
        @Query("category") category: String? = null,
        @Query("q") query: String? = null,
        @Query("openNow") openNow: Boolean? = null,
        @Query("maxDistance") maxDistanceMeters: Int? = null,
        @Query("minRating") minRating: Double? = null,
        @Query("sort") sort: String? = null,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = DEFAULT_PAGE_SIZE,
    ): PlacePageDto

    @GET("places/{id}")
    suspend fun place(@Path("id") id: String): PlaceDto

    @GET("places/{id}/reviews")
    suspend fun reviews(
        @Path("id") id: String,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = DEFAULT_REVIEWS_SIZE,
    ): ReviewPageDto

    companion object {
        const val DEFAULT_PAGE_SIZE = 20
        const val DEFAULT_REVIEWS_SIZE = 10
    }
}
