package uz.mahalla.feature.business.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import uz.mahalla.data.network.ApiResponse

/**
 * Бизнес-панель заведения (эпик #16): метрики, очередь, входящие заказы, меню.
 *
 * Контракт снят с живого `/v3/api-docs` **2026-09-09** и каждый путь проверен
 * curl'ом: все шесть отвечают `401 UNAUTHORIZED`, то есть существуют и требуют
 * Bearer. Поэтому API создаётся на **основном** Retrofit, а не на «голом»
 * `@RefreshClient`. Тела под токеном не проверены — секрета
 * `CONTRACT_REFRESH_TOKEN` в проекте пока нет (см. `docs/API-CONTRACT.md`).
 *
 * **Три ручки принимают безымянную `Map`, и это дефект схемы, а не выбор
 * клиента.** У `accept`, `decline` и смены статуса заказа тело объявлено как
 * `additionalProperties` (`Map<String, String>` / `Map<String, Integer>`) —
 * springdoc не знает имён ключей, потому что контроллер принимает голую
 * `Map`. Как эти имена выведены — в комментариях к каждому запросу ниже.
 */
interface BusinessApi {

    /**
     * Метрики дня (задача 12.1).
     *
     * Ответ — `ApiResponseMapStringLong`, то есть **словарь без схемы**:
     * `additionalProperties: integer(int64)` и ни одного объявленного ключа.
     * Разбираем как есть, `Map<String, Long?>` — придуманные поля дали бы
     * пустой дашборд при первом же расхождении (см. [BusinessDashboard]).
     *
     * `Long?`, а не `Long`: `data` объявлена необязательной, и `null` внутри
     * словаря контракт не запрещает — жёсткий `Long` уронил бы разбор всего
     * ответа из-за одной метрики.
     */
    @GET("analytics/places/{placeId}/dashboard")
    suspend fun dashboard(@Path("placeId") placeId: String): ApiResponse<Map<String, Long?>>

    /**
     * Живая очередь заведения (задача 12.2).
     *
     * `placeId` — **query**, а не path: единственная ручка walk-in, устроенная
     * так. Ответ — `ApiResponseListWalkInResponse`, та же схема
     * `WalkInResponse`, что у клиентского талона (issue #96).
     */
    @GET("walkin/barber/dashboard")
    suspend fun queue(@Query("placeId") placeId: String): ApiResponse<List<QueueEntryDto>>

    /**
     * Принять запрос.
     *
     * Тело — `Map<String, Integer>`; судя по `WalkInResponse.counterTime`, это
     * встречное предложение по времени. Имени ключа схема не знает, поэтому
     * приложение шлёт **пустой объект**: обычное «принять без встречного
     * предложения» — ровно то, что нужно панели, а угаданный ключ бэкенд
     * молча выбросил бы (`Map.get` вернёт `null`), и встречное время исчезло
     * бы бесследно. Предлагать другое время из панели поэтому пока нечем —
     * заведено отдельным issue.
     */
    @PUT("walkin/{id}/accept")
    suspend fun accept(
        @Path("id") ticketId: String,
        @Query("placeId") placeId: String,
        @Body body: Map<String, Int>,
    ): ApiResponse<QueueEntryDto>

    /**
     * Отказать (оно же «пропустить»: ручки для неявки у бэкенда нет).
     *
     * Тело — `Map<String, String>`, по всей видимости причина отказа
     * (`WalkInResponse.barberNote`? `reason`, как у `ModerateRequest`?).
     * Ключ не подтверждён, поэтому шлём пустой объект и поля «причина» в UI
     * не показываем: поле, текст которого сервер выбросит, обещает человеку
     * разговор, которого не будет.
     */
    @PUT("walkin/{id}/decline")
    suspend fun decline(
        @Path("id") ticketId: String,
        @Query("placeId") placeId: String,
        @Body body: Map<String, String>,
    ): ApiResponse<QueueEntryDto>

    /** Вызвать: человек садится в кресло. Тела у ручки нет вовсе. */
    @PUT("walkin/{id}/start")
    suspend fun start(
        @Path("id") ticketId: String,
        @Query("placeId") placeId: String,
    ): ApiResponse<QueueEntryDto>

    /** Обслужен. Тела у ручки нет вовсе. */
    @PUT("walkin/{id}/complete")
    suspend fun complete(
        @Path("id") ticketId: String,
        @Query("placeId") placeId: String,
    ): ApiResponse<QueueEntryDto>

    /**
     * Входящие заказы (задача 12.3). `status` необязателен — без него приходят
     * все.
     */
    @GET("food/places/{placeId}/orders")
    suspend fun orders(
        @Path("placeId") placeId: String,
        @Query("status") status: String?,
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ApiResponse<BusinessOrderPageDto>

    /**
     * Сменить статус заказа.
     *
     * Тело — `Map<String, String>`, и здесь ключ **выведен, а не угадан**:
     * ровно эту операцию у двух других вертикалей того же бэкенда описывают
     * настоящие схемы — `UpdateOrderStatusRequest` (`freelancers/orders/{id}/
     * status`) и `ModerateRequest` (`admin/places/{id}/status`), и в обеих
     * единственное обязательное поле называется **`status`**. То же решение,
     * что для полей `CreatePlaceRequest` в issue #84: имя берётся из
     * соседнего эндпоинта, а не из головы.
     */
    @PUT("food/places/{placeId}/orders/{orderId}/status")
    suspend fun updateOrderStatus(
        @Path("placeId") placeId: String,
        @Path("orderId") orderId: String,
        @Body body: Map<String, String>,
    ): ApiResponse<BusinessOrderDto>

    /**
     * Меню заведения (задача 12.4). Та же ручка, что у витрины клиента: своего
     * «меню для владельца» у бэкенда нет, а стоп-лист виден и здесь
     * (`isAvailable`).
     */
    @GET("food/places/{placeId}/menu")
    suspend fun menu(@Path("placeId") placeId: String): ApiResponse<List<MenuSectionDto>>

    /**
     * Стоп-лист — **переключатель**, как «открыто сейчас» у заведения (issue
     * #94): желаемого состояния в теле нет, бэкенд переворачивает флаг сам.
     *
     * Ответ — `ApiResponseVoid`, то есть нового значения он не сообщает вовсе.
     * Поэтому [BusinessRepository] после успеха считает флаг перевёрнутым, а
     * список перечитывает при следующем возврате на экран.
     */
    @PUT("food/items/{itemId}/toggle")
    suspend fun toggleItem(@Path("itemId") itemId: String): ApiResponse<JsonElement>

    /**
     * Новая позиция меню. `menuId` — идентификатор **раздела**, а не
     * заведения: у бэкенда «меню» это категория, и позиций вне категории не
     * бывает.
     */
    @POST("food/places/{placeId}/items")
    suspend fun createItem(
        @Path("placeId") placeId: String,
        @Body body: CreateMenuItemRequest,
    ): ApiResponse<MenuItemDto>
}

/**
 * `WalkInResponse` — общая схема талона. Имена совпадают с клиентским
 * `WalkInTicketDto` (issue #96), но DTO своё: у панели другой набор нужных
 * полей (`userName` обязателен для показа, `placeName` не нужен вовсе), а
 * общий класс на два экрана разошёлся бы при первой правке.
 *
 * `counterTime` — `JsonElement`: Jackson сериализует `LocalTime` то строкой
 * `"14:30"`, то массивом `[14, 30]`, и разбирает это `parseServerLocalTime`.
 */
@Serializable
data class QueueEntryDto(
    @SerialName("id") val id: String? = null,
    @SerialName("placeId") val placeId: String? = null,
    @SerialName("userId") val userId: String? = null,
    @SerialName("userName") val userName: String? = null,
    @SerialName("serviceName") val serviceName: String? = null,
    @SerialName("barberNote") val barberNote: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("counterTime") val counterTime: JsonElement? = null,
    @SerialName("queuePosition") val queuePosition: Int? = null,
    @SerialName("estimatedWaitMinutes") val estimatedWaitMinutes: Int? = null,
    @SerialName("serviceStartedAt") val serviceStartedAt: String? = null,
    @SerialName("createdAt") val createdAt: String? = null,
)

/** `PageResponseFoodOrderResponse`. */
@Serializable
data class BusinessOrderPageDto(
    @SerialName("content") val content: List<BusinessOrderDto> = emptyList(),
    @SerialName("page") val page: Int? = null,
    @SerialName("size") val size: Int? = null,
    @SerialName("totalElements") val totalElements: Long? = null,
    @SerialName("totalPages") val totalPages: Int? = null,
    @SerialName("first") val first: Boolean? = null,
    @SerialName("last") val last: Boolean? = null,
)

/**
 * `FoodOrderResponse`. Все поля необязательные: разбор в проекте мягкий, и
 * одно пропавшее поле не должно прятать заказ от кухни.
 */
@Serializable
data class BusinessOrderDto(
    @SerialName("id") val id: String? = null,
    @SerialName("placeId") val placeId: String? = null,
    @SerialName("userId") val userId: String? = null,
    @SerialName("staffId") val staffId: String? = null,
    @SerialName("orderNumber") val orderNumber: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("fulfillment") val fulfillment: String? = null,
    @SerialName("paymentMethod") val paymentMethod: String? = null,
    @SerialName("itemsAmount") val itemsAmount: Long? = null,
    @SerialName("deliveryAmount") val deliveryAmount: Long? = null,
    @SerialName("discountAmount") val discountAmount: Long? = null,
    @SerialName("totalAmount") val totalAmount: Long? = null,
    @SerialName("deliveryAddress") val deliveryAddress: String? = null,
    @SerialName("items") val items: List<BusinessOrderItemDto> = emptyList(),
    @SerialName("createdAt") val createdAt: String? = null,
)

/** `FoodOrderItemResponse`. */
@Serializable
data class BusinessOrderItemDto(
    @SerialName("itemId") val itemId: String? = null,
    @SerialName("itemName") val itemName: String? = null,
    @SerialName("quantity") val quantity: Int? = null,
    @SerialName("unitPrice") val unitPrice: Long? = null,
    @SerialName("totalPrice") val totalPrice: Long? = null,
)

/** `MenuResponse`: раздел меню вместе с позициями. */
@Serializable
data class MenuSectionDto(
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("items") val items: List<MenuItemDto> = emptyList(),
)

/**
 * `ItemResponse`.
 *
 * Флаг стоп-листа принимается под двумя именами: Jackson сериализует
 * `boolean isAvailable` то как `isAvailable`, то как `available` — та же
 * страховка, что у витрины «Еды» (issue #9) и у «моих заведений» (issue #94).
 * Ошибка здесь увела бы в стоп-лист всё меню разом.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class MenuItemDto(
    @SerialName("id") val id: String? = null,
    @SerialName("menuId") val menuId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("price") val price: Long? = null,
    @SerialName("prepMinutes") val prepMinutes: Int? = null,
    @JsonNames("available") @SerialName("isAvailable") val isAvailable: Boolean? = null,
    @JsonNames("halal") @SerialName("isHalal") val isHalal: Boolean? = null,
)

/**
 * `CreateItemRequest`. Обязательны `menuId`, `name`, `price` (`@Min(1000)`);
 * остальное уходит **отсутствующим**, а не `null` — `explicitNulls = false`
 * выбрасывает пустые поля из тела.
 */
@Serializable
data class CreateMenuItemRequest(
    @SerialName("menuId") val menuId: String,
    @SerialName("name") val name: String,
    @SerialName("price") val price: Long,
    @SerialName("description") val description: String? = null,
    @SerialName("prepMinutes") val prepMinutes: Int? = null,
    @SerialName("isHalal") val isHalal: Boolean? = null,
)
