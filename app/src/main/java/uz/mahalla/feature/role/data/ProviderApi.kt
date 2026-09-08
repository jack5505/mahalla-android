package uz.mahalla.feature.role.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import uz.mahalla.data.network.ApiResponse

/**
 * Регистрация заведения продавцом (issue #84) и его заведения (issue #94).
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

    /**
     * «Мои заведения» (issue #94). В `CLAUDE.md` было записано, что этой ручки
     * у бэкенда нет вовсе — сверка по полной схеме (#92) показала обратное.
     *
     * Ответ — `ApiResponsePageResponseMine`, то есть конверт с настоящей
     * пагинацией. Схемы `PageResponseMine` и `Mine` встречаются в
     * `/v3/api-docs` по одному разу, коллизии springdoc здесь нет.
     *
     * **Без валидного токена приходит `500 INTERNAL_ERROR`, а не `401`**
     * (проверено curl'ом) — дефект бэкенда, заведённый в §6
     * `docs/BACKEND-SYNC.md`. Для приложения это значит, что «технический
     * сбой» на этом экране может оказаться обычным истёкшим входом.
     */
    @GET("places/my")
    suspend fun myPlaces(
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ApiResponse<MyPlacePageDto>

    /**
     * «Открыто сейчас» — **переключатель**, а не установка значения: желаемого
     * состояния в теле нет вовсе, бэкенд сам меняет флаг на противоположный.
     * Ответ — `ApiResponseBoolean`; читаем его как новое состояние.
     *
     * Единственная ручка этой задачи, которая без токена отвечает правильно
     * (`401 UNAUTHORIZED`).
     */
    @PUT("places/{id}/availability")
    suspend fun toggleAvailability(
        @Path("id") placeId: String,
        @Body body: ToggleAvailabilityRequest,
    ): ApiResponse<Boolean>
}

/**
 * `ToggleAvailabilityRequest`: `lat` и `lng` **обязательны** (schema
 * `required`), желаемого состояния в теле нет.
 *
 * Координаты берутся у устройства (`RequestLocationProvider`, та же лестница
 * «позиция → центр города → Ташкент», что у запросов авторизации), а не у
 * самого заведения. Если бэкенд проверяет, что владелец рядом с точкой,
 * подставить координаты заведения значило бы обойти эту проверку.
 */
@Serializable
data class ToggleAvailabilityRequest(
    @SerialName("lat") val lat: Double,
    @SerialName("lng") val lng: Double,
)

/** `PageResponseMine`. */
@Serializable
data class MyPlacePageDto(
    @SerialName("content") val content: List<MyPlaceDto> = emptyList(),
    @SerialName("page") val page: Int? = null,
    @SerialName("size") val size: Int? = null,
    @SerialName("totalElements") val totalElements: Long? = null,
    @SerialName("totalPages") val totalPages: Int? = null,
    @SerialName("first") val first: Boolean? = null,
    @SerialName("last") val last: Boolean? = null,
)

/**
 * `Mine`. Все поля необязательные: отсутствие любого из них — не повод
 * показать экран ошибки вместо списка.
 *
 * `isAvailable` принимается и под именем `available`: Jackson сериализует
 * `boolean isAvailable` то так, то так, в зависимости от геттера, а ошибка
 * здесь показала бы закрытыми все заведения (то же правило, что у `isRead` в
 * issue #81 и `isAvailable` меню в issue #9).
 *
 * `logoUrl` и `subscriptionPlan` объявлены, но в домен не доезжают: картинки
 * показывать пока нечем (загрузчика изображений в проекте нет), а тарифы —
 * это бизнес-панель (эпик #16). Здесь они документируют контракт.
 */
@Serializable
data class MyPlaceDto(
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("category") val category: String? = null,
    @SerialName("address") val address: String? = null,
    @SerialName("lat") val lat: Double? = null,
    @SerialName("lng") val lng: Double? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("isAvailable") val isAvailable: Boolean? = null,
    @SerialName("available") val available: Boolean? = null,
    @SerialName("ratingAvg") val ratingAvg: Double? = null,
    @SerialName("ratingCount") val ratingCount: Int? = null,
    @SerialName("logoUrl") val logoUrl: String? = null,
    @SerialName("subscriptionPlan") val subscriptionPlan: String? = null,
    @SerialName("role") val role: String? = null,
)

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
