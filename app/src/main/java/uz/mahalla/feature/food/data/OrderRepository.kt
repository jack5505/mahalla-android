package uz.mahalla.feature.food.data

import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.map
import uz.mahalla.core.result.runCatchingCancellable
import uz.mahalla.data.db.dao.OrderDao
import uz.mahalla.data.db.dao.PlaceDao
import uz.mahalla.data.network.payload
import uz.mahalla.feature.food.domain.Cart
import uz.mahalla.feature.food.domain.CartLine
import uz.mahalla.feature.food.domain.CheckoutForm
import uz.mahalla.feature.food.domain.Order
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Заказы вертикали «Еда» (эпик 5.3/5.4) — под контракт бэкенда (issue #63).
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
    private val placeDao: PlaceDao,
    private val cartRepository: CartRepository,
) : OrderRepository {

    /**
     * В заказ уходит только то, что бэкенд принимает: заведение, позиции,
     * способ получения, способ оплаты и адрес. Промокод, время и комментарий
     * контракт не знает — на экране их поэтому и не спрашивают.
     */
    override suspend fun create(cart: Cart, form: CheckoutForm): ApiResult<Order> {
        val result = apiCall {
            api.createOrder(
                PlaceOrderDto(
                    placeId = cart.placeId,
                    items = cart.lines.map(CartLine::toRequestDto),
                    fulfillment = form.method.apiValue,
                    paymentMethod = form.payment.apiValue,
                    deliveryAddress = form.address.trim()
                        .takeIf { form.needsAddress && it.isNotEmpty() },
                ),
            ).payload()
        }.map { dto -> dto.toDomain(placeName = cart.placeName) }

        if (result is ApiResult.Success) {
            cache(result.data)
            cartRepository.clear(cart.placeId)
        }
        return result
    }

    override suspend fun order(orderId: String): ApiResult<Order> =
        apiCall { api.order(orderId).payload() }.withPlaceName().alsoCache()

    override suspend fun cancel(orderId: String): ApiResult<Order> =
        apiCall { api.cancelOrder(orderId).payload() }.withPlaceName().alsoCache()

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

    /**
     * Названия заведения в `OrderResponse` нет. Берём из кэша каталога — там
     * запись появляется, когда человек открывает карточку места, то есть до
     * всякого заказа. Нет записи — пустое имя: шапка покажет заголовок экрана,
     * а не чужое название.
     */
    private suspend fun ApiResult<OrderDto>.withPlaceName(): ApiResult<Order> = map { dto ->
        dto.toDomain(placeName = placeName(dto.placeId))
    }

    private suspend fun placeName(placeId: String): String =
        runCatchingCancellable { placeDao.byId(placeId)?.name }.getOrNull().orEmpty()

    private suspend fun ApiResult<Order>.alsoCache(): ApiResult<Order> = also {
        if (it is ApiResult.Success) cache(it.data)
    }

    private suspend fun cache(order: Order) = orderDao.upsert(listOf(order.toEntity()))
}
