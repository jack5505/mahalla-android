package uz.mahalla.feature.food.data

import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.map
import uz.mahalla.core.result.runCatchingCancellable
import uz.mahalla.data.db.dao.OrderDao
import uz.mahalla.feature.food.domain.Cart
import uz.mahalla.feature.food.domain.CartLine
import uz.mahalla.feature.food.domain.CheckoutForm
import uz.mahalla.feature.food.domain.Order
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Заказы вертикали «Еда» (эпик 5.3/5.4).
 *
 * Успешно созданный заказ чистит черновик корзины: оставить его значит дать
 * человеку оформить тот же заказ второй раз, просто нажав «назад».
 */
interface OrderRepository {

    suspend fun create(cart: Cart, form: CheckoutForm): ApiResult<Order>

    suspend fun order(orderId: String): ApiResult<Order>

    suspend fun cancel(orderId: String): ApiResult<Order>

    /**
     * Повтор заказа: позиции старого заказа возвращаются в корзину. `null` —
     * собрать корзину не удалось (база недоступна), прежний черновик при этом
     * остался на месте.
     */
    suspend fun repeat(order: Order): List<CartLine>?
}

@Singleton
class DefaultOrderRepository @Inject constructor(
    private val api: FoodApi,
    private val orderDao: OrderDao,
    private val cartRepository: CartRepository,
) : OrderRepository {

    override suspend fun create(cart: Cart, form: CheckoutForm): ApiResult<Order> {
        val result = apiCall {
            api.createOrder(
                CreateOrderDto(
                    placeId = cart.placeId,
                    items = cart.lines.map(CartLine::toDto),
                    method = form.method.apiValue,
                    payment = form.payment.apiValue,
                    address = form.address.trim().takeIf { form.needsAddress && it.isNotEmpty() },
                    comment = form.comment.trim().takeIf(String::isNotEmpty),
                    scheduledAt = form.scheduledAt
                        ?.takeIf { !form.asap }
                        ?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    promoCode = cart.promo?.code,
                ),
            )
        }.map(OrderDto::toDomain)

        if (result is ApiResult.Success) {
            cache(result.data)
            cartRepository.clear(cart.placeId)
        }
        return result
    }

    override suspend fun order(orderId: String): ApiResult<Order> =
        apiCall { api.order(orderId) }.map(OrderDto::toDomain).alsoCache()

    override suspend fun cancel(orderId: String): ApiResult<Order> =
        apiCall { api.cancelOrder(orderId) }.map(OrderDto::toDomain).alsoCache()

    /**
     * Позиции старого заказа как строки корзины. Цены берутся из заказа, а не
     * из меню: меню могло измениться, а положить в корзину нечего иначе —
     * сверку с актуальным меню делает экран корзины при следующем открытии.
     */
    override suspend fun repeat(order: Order): List<CartLine>? =
        runCatchingCancellable {
            // Одной транзакцией: прежний черновик исчезает только вместе с
            // появлением нового. Раньше `clearAll()` шёл первым, и падение на
            // добавлении оставляло человека с половиной корзины вместо двух.
            cartRepository.replace(
                placeId = order.placeId,
                placeName = order.placeName,
                deliverySum = order.totals.deliverySum,
                lines = order.lines,
            )
            order.lines
        }.getOrNull()

    private suspend fun ApiResult<Order>.alsoCache(): ApiResult<Order> = also {
        if (it is ApiResult.Success) cache(it.data)
    }

    private suspend fun cache(order: Order) = orderDao.upsert(listOf(order.toEntity()))
}
