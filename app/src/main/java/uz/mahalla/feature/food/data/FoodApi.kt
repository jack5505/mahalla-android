package uz.mahalla.feature.food.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import uz.mahalla.data.network.ApiResponse

/**
 * Вертикаль «Еда» (эпик 5): меню, заказы.
 *
 * Контракт снят со стенда (`/v3/api-docs` + прямые curl'ы) — прежние пути
 * (`places/{id}/menu`, `orders`) были подобраны по образцу каталога и у
 * бэкенда не существуют вовсе, то есть вертикаль не работала ни на одном
 * экране.
 *
 * Ответы приходят в общем конверте `{success, data, error}` (issue #42),
 * поэтому каждый вызов заканчивается `payload()`/`ensureSuccess()`.
 *
 * Читать заказ идём в **общий** `orders/{orderId}` (order-controller), а не в
 * `food/orders/{orderId}`: у первого ответ описан схемой `OrderView` со всеми
 * суммами, а у второго имя схемы `OrderResponse` в `/v3/api-docs` перекрыто
 * коллизией springdoc (под ним лежит заказ фрилансера), то есть имена полей
 * оттуда взять нельзя.
 */
interface FoodApi {

    /**
     * Меню заведения. `data` — список «меню» заведения, и каждое из них
     * работает как категория: у него есть название и позиции.
     */
    @GET("food/places/{placeId}/menu")
    suspend fun menu(@Path("placeId") placeId: String): ApiResponse<List<MenuSectionDto>>

    @POST("food/orders")
    suspend fun createOrder(@Body request: PlaceOrderRequestDto): ApiResponse<CreatedOrderDto>

    @GET("orders/{orderId}")
    suspend fun order(@Path("orderId") orderId: String): ApiResponse<OrderViewDto>

    /**
     * Отмена. Тело ответа не разбирается (та же коллизия схемы) — состояние
     * заказа перечитывается [order]'ом: догадываться о полях там, где ошибка
     * превратит удачную отмену в «не удалось», незачем.
     */
    @POST("food/orders/{orderId}/cancel")
    suspend fun cancelOrder(@Path("orderId") orderId: String): ApiResponse<JsonElement>
}

/** `MenuResponse` бэкенда: категория меню вместе с позициями. */
@Serializable
data class MenuSectionDto(
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("items") val items: List<MenuItemDto> = emptyList(),
)

/**
 * `ItemResponse` бэкенда.
 *
 * Флаг стоп-листа принимается под двумя именами: Jackson сериализует
 * `boolean isAvailable` то как `isAvailable`, то как `available`, и ошибка
 * здесь увела бы в стоп-лист всё меню (то же правило, что у `isRead` в
 * уведомлениях, issue #81).
 *
 * Модификаторов (`optionGroups`) в контракте нет — см. `FoodMappers`.
 */
@Serializable
data class MenuItemDto(
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("price") val price: Long? = null,
    @SerialName("prepMinutes") val prepMinutes: Int? = null,
    @SerialName("isAvailable") val isAvailable: Boolean? = null,
    @SerialName("available") val available: Boolean? = null,
    @SerialName("isHalal") val isHalal: Boolean? = null,
)

/**
 * `PlaceOrderRequest` бэкенда. Больше в заказ положить нечего: ни промокода,
 * ни комментария, ни времени, ни модификаторов позиции контракт не принимает.
 */
@Serializable
data class PlaceOrderRequestDto(
    @SerialName("placeId") val placeId: String,
    @SerialName("items") val items: List<OrderItemRequestDto> = emptyList(),
    /** `DELIVERY` / `PICKUP` / `DINE_IN`. */
    @SerialName("fulfillment") val fulfillment: String,
    /** `WALLET` / `CASH`. */
    @SerialName("paymentMethod") val paymentMethod: String,
    @SerialName("deliveryAddress") val deliveryAddress: String? = null,
)

/** Цену и состав считает сервер: клиент присылает только позицию и количество. */
@Serializable
data class OrderItemRequestDto(
    @SerialName("itemId") val itemId: String,
    @SerialName("quantity") val quantity: Int,
)

/**
 * Ответ на создание заказа. Разбирается только идентификатор — по нему экран
 * статуса читает заказ целиком. `orderId` принимается вторым именем на случай,
 * если у food-контроллера поле названо не как во всех остальных ответах.
 */
@Serializable
data class CreatedOrderDto(
    @SerialName("id") val id: String? = null,
    @SerialName("orderId") val orderId: String? = null,
)

/** `OrderView` бэкенда: единый вид заказа для всех вертикалей. */
@Serializable
data class OrderViewDto(
    @SerialName("id") val id: String? = null,
    @SerialName("orderNumber") val orderNumber: String? = null,
    @SerialName("placeId") val placeId: String? = null,
    @SerialName("vertical") val vertical: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("fulfillment") val fulfillment: String? = null,
    @SerialName("paymentMethod") val paymentMethod: String? = null,
    @SerialName("itemsAmount") val itemsAmount: Long? = null,
    @SerialName("deliveryAmount") val deliveryAmount: Long? = null,
    @SerialName("discountAmount") val discountAmount: Long? = null,
    @SerialName("totalAmount") val totalAmount: Long? = null,
    @SerialName("deliveryAddress") val deliveryAddress: String? = null,
    /** ISO-8601; Jackson отдаёт и без зоны — разбирает `parseServerInstant`. */
    @SerialName("createdAt") val createdAt: String? = null,
    @SerialName("items") val items: List<OrderItemViewDto> = emptyList(),
)

/** `ItemView` бэкенда: строка заказа. */
@Serializable
data class OrderItemViewDto(
    @SerialName("itemType") val itemType: String? = null,
    @SerialName("itemId") val itemId: String? = null,
    @SerialName("itemName") val itemName: String? = null,
    @SerialName("quantity") val quantity: Int? = null,
    @SerialName("unitPrice") val unitPrice: Long? = null,
    @SerialName("totalPrice") val totalPrice: Long? = null,
)
