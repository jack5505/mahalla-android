package uz.mahalla.feature.food.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import uz.mahalla.data.network.ApiResponse

/**
 * Вертикаль «Еда» (эпик 5) — под реальный контракт бэкенда (issue #63).
 *
 * Прежняя версия была написана по здравому смыслу (design-репозиторий тогда был
 * недоступен) и на живом сервере не работала ни одним вызовом: пути другие, а
 * ответы приезжают в общем конверте [ApiResponse], а не «голым» JSON.
 *
 * Контракт снят с `https://189-74-96-232.nip.io/v3/api-docs` и проверен
 * curl'ами: меню отвечает **без токена** (`{"success":true,"data":[]}` для
 * неизвестного заведения — не 404), все ручки заказов требуют Bearer.
 * Промокод живёт не в «Еде», а в общем модуле акций (`GET promotions/check`),
 * поэтому объявлен здесь же: другого потребителя у него в приложении нет.
 */
interface FoodApi {

    @GET("food/places/{placeId}/menu")
    suspend fun menu(@Path("placeId") placeId: String): ApiResponse<List<MenuSectionDto>>

    /**
     * Проверка промокода. Сумма позиций уходит на сервер: скидку считает он,
     * и `discountAmount` в ответе — уже готовое число, а не правило.
     */
    @GET("promotions/check")
    suspend fun checkPromo(
        @Query("code") code: String,
        @Query("placeId") placeId: String,
        @Query("orderAmount") orderAmountSum: Long,
    ): ApiResponse<PromoCheckDto>

    @POST("food/orders")
    suspend fun createOrder(@Body request: PlaceOrderDto): ApiResponse<OrderDto>

    @GET("food/orders/{orderId}")
    suspend fun order(@Path("orderId") orderId: String): ApiResponse<OrderDto>

    @POST("food/orders/{orderId}/cancel")
    suspend fun cancelOrder(@Path("orderId") orderId: String): ApiResponse<OrderDto>
}

/**
 * Раздел меню (`MenuResponse`). У бэкенда «меню» и есть категория: заведение
 * заводит несколько меню, каждое со своим набором позиций.
 */
@Serializable
data class MenuSectionDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String = "",
    @SerialName("description") val description: String? = null,
    @SerialName("items") val items: List<MenuItemDto> = emptyList(),
)

/**
 * Позиция меню (`ItemResponse`).
 *
 * Модификаторов («размер», «добавки») в контракте нет вовсе — как и фотографии
 * позиции. Поля не выдумываем: отправить выбранные модификаторы всё равно
 * некуда, `PlaceOrderRequest` принимает только `itemId` и количество.
 */
@Serializable
data class MenuItemDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String = "",
    @SerialName("description") val description: String? = null,
    @SerialName("price") val price: Long = 0,
    @SerialName("prepMinutes") val prepMinutes: Int? = null,
    /** Стоп-лист: позиция остаётся в меню, но неактивна. */
    @SerialName("isAvailable") val isAvailable: Boolean = true,
    @SerialName("isHalal") val isHalal: Boolean = false,
)

/** Ответ `promotions/check`. `valid = false` — код существует, но не подошёл. */
@Serializable
data class PromoCheckDto(
    @SerialName("valid") val valid: Boolean = false,
    @SerialName("discountAmount") val discountAmount: Long = 0,
    @SerialName("finalAmount") val finalAmount: Long = 0,
    @SerialName("promoCode") val promoCode: String? = null,
)

/**
 * Тело `POST food/orders` (`PlaceOrderRequest`).
 *
 * Больше в заказ положить нечего: ни промокода, ни времени доставки, ни
 * комментария, ни модификаторов позиции контракт не принимает.
 */
@Serializable
data class PlaceOrderDto(
    @SerialName("placeId") val placeId: String,
    @SerialName("items") val items: List<OrderItemRequestDto> = emptyList(),
    @SerialName("fulfillment") val fulfillment: String,
    @SerialName("paymentMethod") val paymentMethod: String,
    @SerialName("deliveryAddress") val deliveryAddress: String? = null,
)

@Serializable
data class OrderItemRequestDto(
    @SerialName("itemId") val itemId: String,
    @SerialName("quantity") val quantity: Int,
)

/**
 * Заказ (`OrderResponse`).
 *
 * Названия заведения здесь нет — только [placeId]; суммы разложены самим
 * бэкендом, и [totalAmount] важнее собственной арифметики приложения: платит
 * человек по его числу.
 */
@Serializable
data class OrderDto(
    @SerialName("id") val id: String,
    @SerialName("placeId") val placeId: String = "",
    /** Номер для человека («A-1042») — его называют на выдаче. */
    @SerialName("orderNumber") val orderNumber: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("fulfillment") val fulfillment: String? = null,
    @SerialName("paymentMethod") val paymentMethod: String? = null,
    @SerialName("itemsAmount") val itemsAmount: Long = 0,
    @SerialName("deliveryAmount") val deliveryAmount: Long = 0,
    @SerialName("discountAmount") val discountAmount: Long = 0,
    @SerialName("totalAmount") val totalAmount: Long = 0,
    @SerialName("deliveryAddress") val deliveryAddress: String? = null,
    @SerialName("items") val items: List<OrderItemDto> = emptyList(),
    /** ISO-8601; Jackson на бэкенде отдаёт его без зоны — см. `parseServerInstant`. */
    @SerialName("createdAt") val createdAt: String? = null,
)

@Serializable
data class OrderItemDto(
    @SerialName("itemId") val itemId: String = "",
    @SerialName("itemName") val itemName: String = "",
    @SerialName("quantity") val quantity: Int = 1,
    @SerialName("unitPrice") val unitPrice: Long = 0,
    @SerialName("totalPrice") val totalPrice: Long = 0,
)
