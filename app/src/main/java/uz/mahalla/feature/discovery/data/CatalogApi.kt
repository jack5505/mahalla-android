package uz.mahalla.feature.discovery.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

@Serializable
data class PlaceDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("category") val category: String = "",
    @SerialName("rating") val rating: Double = 0.0,
    @SerialName("distanceMeters") val distanceMeters: Int = 0,
    @SerialName("isOpenNow") val isOpenNow: Boolean = false,
)

/**
 * Каталог мест. Полноценные экраны discovery — эпик 3; здесь API нужен как
 * первый реальный потребитель сетевого слоя (и как объект тестов на
 * MockWebServer).
 */
interface CatalogApi {

    /** `category = null` — без фильтра (Retrofit просто не добавит параметр). */
    @GET("places")
    suspend fun places(@Query("category") category: String?): List<PlaceDto>

    @GET("places/{id}")
    suspend fun place(@Path("id") id: String): PlaceDto
}
