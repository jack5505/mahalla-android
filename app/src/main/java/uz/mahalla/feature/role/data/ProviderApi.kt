package uz.mahalla.feature.role.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST
import uz.mahalla.data.network.ApiResponse

/**
 * Регистрация заведения продавцом (issue #84).
 *
 * Контракт снят со стенда (`/v3/api-docs` + curl): `POST /api/v1/places`
 * требует Bearer — без токена приходит `401 UNAUTHORIZED`, — поэтому API
 * создаётся на **основном** Retrofit, а не на «голом» `@RefreshClient`.
 * Гео-заголовки тоже обязательны (без них `403 GEO_PERMISSION_REQUIRED`), но
 * их ставит `GeoHeaderInterceptor` на обоих клиентах (issue #53).
 *
 * **Имена полей запроса выведены из ответа, а не из схемы.** В `/v3/api-docs`
 * тело объявлено как `CreateRequest`, а это имя перекрыто коллизией springdoc
 * — под ним лежат пять разных запросов, и уцелел вариант отзыва (`placeId` +
 * `rating`). Живым запросом форму тела тоже не проверить: `401` приходит до
 * валидации. Поэтому поля названы так же, как в ответе `Detail`, который
 * бэкенд отдаёт на этот же эндпоинт (`name`, `category`, `description`,
 * `address`, `lat`, `lng`, `city`, `phone`, `website`) — то же решение, что
 * принято для отзывов в issue #76.
 */
interface ProviderApi {

    @POST("places")
    suspend fun createPlace(@Body body: CreatePlaceRequest): ApiResponse<PlaceDetailDto>
}

/**
 * Пустые необязательные поля уходят **отсутствующими**, а не `null`:
 * `explicitNulls = false` в конфигурации Json выбрасывает их из тела, и
 * бэкенд получает ровно то, что человек заполнил.
 */
@Serializable
data class CreatePlaceRequest(
    @SerialName("name") val name: String,
    /** Значение перечисления бэкенда: `FOOD`, `PHARMACY`, `BARBER`, … */
    @SerialName("category") val category: String,
    @SerialName("address") val address: String,
    @SerialName("lat") val lat: Double,
    @SerialName("lng") val lng: Double,
    @SerialName("phone") val phone: String,
    @SerialName("city") val city: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("website") val website: String? = null,
)

/**
 * `Detail` — карточка заведения в ответе. Все поля необязательные: заявка
 * принята, и отсутствие одного из них не повод показать ошибку вместо
 * подтверждения.
 */
@Serializable
data class PlaceDetailDto(
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("category") val category: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("address") val address: String? = null,
    @SerialName("city") val city: String? = null,
    @SerialName("phone") val phone: String? = null,
    @SerialName("website") val website: String? = null,
    @SerialName("ownerId") val ownerId: String? = null,
)
