package uz.mahalla.feature.food.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Вертикаль «Еда» (эпик 5): меню, промокод, заказы.
 *
 * Контракт бэкенда по этим ручкам ещё не сверен с `MAHALLA-IMPLEMENTATION.md`
 * (в этом прогоне design-репозиторий недоступен) — имена полей подобраны по
 * образцу каталога эпика 4. Все необязательные поля имеют дефолт: отсутствие
 * поля не должно ронять экран.
 */
interface FoodApi {

    @GET("places/{id}/menu")
    suspend fun menu(@Path("id") placeId: String): MenuDto

    /**
     * Проверка промокода. Сумма позиций уходит на сервер: скидка зависит от
     * неё, и решать «подходит ли код» обязан тот, кто потом выставит счёт.
     */
    @POST("places/{id}/promo")
    suspend fun promo(
        @Path("id") placeId: String,
        @Body request: PromoRequestDto,
    ): PromoDto

    @POST("orders")
    suspend fun createOrder(@Body request: CreateOrderDto): OrderDto

    @GET("orders/{id}")
    suspend fun order(@Path("id") orderId: String): OrderDto

    @POST("orders/{id}/cancel")
    suspend fun cancelOrder(@Path("id") orderId: String): OrderDto

    @GET("wallet/balance")
    suspend fun walletBalance(): WalletBalanceDto
}

@Serializable
data class MenuDto(
    @SerialName("placeName") val placeName: String = "",
    @SerialName("categories") val categories: List<MenuCategoryDto> = emptyList(),
    /** Стоимость доставки заведения — показывается в корзине до checkout'а. */
    @SerialName("deliveryFee") val deliveryFee: Long = 0,
    @SerialName("minOrder") val minOrder: Long = 0,
)

@Serializable
data class MenuCategoryDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String = "",
    @SerialName("items") val items: List<MenuItemDto> = emptyList(),
)

@Serializable
data class MenuItemDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String = "",
    @SerialName("description") val description: String? = null,
    @SerialName("price") val price: Long = 0,
    @SerialName("photoUrl") val photoUrl: String? = null,
    /** Стоп-лист: позиция остаётся в меню, но неактивна. */
    @SerialName("available") val available: Boolean = true,
    @SerialName("optionGroups") val optionGroups: List<OptionGroupDto> = emptyList(),
)

@Serializable
data class OptionGroupDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String = "",
    @SerialName("minChoices") val minChoices: Int = 0,
    @SerialName("maxChoices") val maxChoices: Int = 1,
    @SerialName("options") val options: List<MenuOptionDto> = emptyList(),
)

@Serializable
data class MenuOptionDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String = "",
    @SerialName("priceDelta") val priceDelta: Long = 0,
    @SerialName("available") val available: Boolean = true,
)

@Serializable
data class PromoRequestDto(
    @SerialName("code") val code: String,
    @SerialName("subtotal") val subtotal: Long,
)

@Serializable
data class PromoDto(
    @SerialName("code") val code: String,
    /** `percent` или `fixed`; незнакомое значение считаем фиксированной суммой. */
    @SerialName("kind") val kind: String = "fixed",
    @SerialName("value") val value: Long = 0,
    @SerialName("minOrder") val minOrder: Long = 0,
    @SerialName("maxDiscount") val maxDiscount: Long? = null,
)

@Serializable
data class CreateOrderDto(
    @SerialName("placeId") val placeId: String,
    @SerialName("items") val items: List<OrderItemDto> = emptyList(),
    @SerialName("method") val method: String,
    @SerialName("payment") val payment: String,
    @SerialName("address") val address: String? = null,
    @SerialName("comment") val comment: String? = null,
    /** ISO-8601 без зоны; `null` — «как можно скорее». */
    @SerialName("scheduledAt") val scheduledAt: String? = null,
    @SerialName("promoCode") val promoCode: String? = null,
)

@Serializable
data class OrderItemDto(
    @SerialName("itemId") val itemId: String,
    @SerialName("name") val name: String = "",
    @SerialName("price") val price: Long = 0,
    @SerialName("quantity") val quantity: Int = 1,
    @SerialName("optionIds") val optionIds: List<String> = emptyList(),
    @SerialName("optionsLabel") val optionsLabel: String = "",
)

@Serializable
data class OrderDto(
    @SerialName("id") val id: String,
    @SerialName("placeId") val placeId: String = "",
    @SerialName("placeName") val placeName: String = "",
    @SerialName("status") val status: String = "",
    @SerialName("method") val method: String = "",
    @SerialName("payment") val payment: String = "",
    @SerialName("subtotal") val subtotal: Long = 0,
    @SerialName("discount") val discount: Long = 0,
    @SerialName("delivery") val delivery: Long = 0,
    @SerialName("items") val items: List<OrderItemDto> = emptyList(),
    /** Секунды эпохи: часовой пояс клиента на историю заказов влиять не должен. */
    @SerialName("createdAt") val createdAt: Long = 0,
    @SerialName("address") val address: String? = null,
    @SerialName("comment") val comment: String? = null,
    @SerialName("etaMinutes") val etaMinutes: Int? = null,
)

@Serializable
data class WalletBalanceDto(
    @SerialName("balance") val balance: Long = 0,
)
