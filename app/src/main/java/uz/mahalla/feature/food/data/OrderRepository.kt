package uz.mahalla.feature.food.data

import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import uz.mahalla.core.crash.reportSwallowed
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.runCatchingCancellable
import uz.mahalla.data.db.dao.OrderDao
import uz.mahalla.data.db.entity.OrderEntity
import uz.mahalla.data.network.ensureSuccess
import uz.mahalla.data.network.payload
import uz.mahalla.feature.food.domain.Cart
import uz.mahalla.feature.food.domain.CartCalculator
import uz.mahalla.feature.food.domain.CartLine
import uz.mahalla.feature.food.domain.CheckoutForm
import uz.mahalla.feature.food.domain.Order
import uz.mahalla.feature.food.domain.OrderStatus

/**
 * Заказы вертикали «Еда» (эпик 5.3/5.4).
 *
 * Успешно созданный заказ чистит черновик корзины: оставить его значит дать
 * человеку оформить тот же заказ второй раз, просто нажав «назад».
 */
interface OrderRepository {

    /**
     * Оформление. Возвращается только идентификатор заказа: ответ
     * `POST food/orders` описан схемой, перекрытой коллизией springdoc, и
     * читать из него что-то кроме id — гадание. Состав и суммы экран статуса
     * берёт у `GET orders/{id}`, где схема однозначна.
     */
    suspend fun create(cart: Cart, form: CheckoutForm): ApiResult<String>

    suspend fun order(orderId: String): ApiResult<Order>

    /**
     * Отмена. Тело ответа не разбирается — новое состояние заказа вызывающий
     * перечитывает [order]'ом. Иначе неудачный разбор ответа выглядел бы как
     * «отменить не удалось», хотя заказ уже отменён.
     */
    suspend fun cancel(orderId: String): ApiResult<Unit>

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
    private val clock: Clock,
) : OrderRepository {

    override suspend fun create(cart: Cart, form: CheckoutForm): ApiResult<String> {
        val result = apiCall {
            api.createOrder(
                PlaceOrderRequestDto(
                    placeId = cart.placeId,
                    items = cart.lines.map(CartLine::toRequest),
                    fulfillment = form.method.apiValue,
                    paymentMethod = form.payment.apiValue,
                    deliveryAddress = form.addressOrNull(),
                ),
            ).payload()
        }

        return when (result) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                val orderId = result.data.id?.takeIf { it.isNotBlank() }
                    ?: result.data.orderId?.takeIf { it.isNotBlank() }
                // Заказ создан, но идентификатора в ответе нет: показать экран
                // статуса нечем, а делать вид, что заказа не было, — тоже
                // неправда. Черновик в этом случае не чистим: он единственное,
                // что останется у человека.
                    ?: return ApiResult.Failure(ApiError.Serialization)
                // Имя заведения знает только корзина — в ответах о заказе его
                // нет ни у одного эндпоинта. Кладём его в кэш заранее, чтобы
                // экран статуса не показывал заказ «неизвестно откуда».
                rememberPlaceName(orderId, cart)
                cartRepository.clear(cart.placeId)
                ApiResult.Success(orderId)
            }
        }
    }

    override suspend fun order(orderId: String): ApiResult<Order> {
        val cached = orderDao.byId(orderId)
        return when (val result = apiCall { api.order(orderId).payload() }) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                val order = result.data.toDomain(placeName = cached?.placeName.orEmpty())
                    ?: return ApiResult.Failure(ApiError.Serialization)
                orderDao.upsert(listOf(order.toEntity()))
                ApiResult.Success(order)
            }
        }
    }

    override suspend fun cancel(orderId: String): ApiResult<Unit> =
        apiCall { api.cancelOrder(orderId).ensureSuccess() }

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
                lines = order.lines,
            )
            order.lines
        }.reportSwallowed("cart.replaceFromOrder").getOrNull()

    private suspend fun rememberPlaceName(orderId: String, cart: Cart) {
        orderDao.upsert(
            listOf(
                OrderEntity(
                    id = orderId,
                    placeId = cart.placeId,
                    placeName = cart.placeName,
                    status = OrderStatus.Created.apiValue,
                    totalSum = CartCalculator.subtotal(cart.lines),
                    createdAtEpochSeconds = clock.instant().epochSecond,
                ),
            ),
        )
    }
}
