package uz.mahalla.feature.fashion.data

import javax.inject.Inject
import javax.inject.Singleton
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.data.network.ensureSuccess
import uz.mahalla.data.network.payload
import uz.mahalla.feature.fashion.domain.CLOTHING_VERTICAL
import uz.mahalla.feature.fashion.domain.FashionCartStore
import uz.mahalla.feature.fashion.domain.FashionOrderPage
import uz.mahalla.feature.food.data.OrderItemRequestDto
import uz.mahalla.feature.food.data.PlaceOrderRequestDto
import uz.mahalla.feature.food.data.toDomain
import uz.mahalla.feature.food.domain.CheckoutForm
import uz.mahalla.feature.food.domain.Order

/**
 * Заказы одежды (issue #108).
 *
 * Оформление идёт по **одному магазину за раз**: корзина на сервере общая, а
 * `PlaceOrderRequest` принимает ровно один `placeId`. Разложить корзину по
 * магазинам умеет домен ([FashionCartStore]).
 *
 * Читается список общим `orders`-контроллером с фильтром `vertical=CLOTHING`:
 * у `fashion/orders/my` схема ответа перекрыта коллизией springdoc (см. KDoc
 * [FashionApi]).
 */
interface FashionOrderRepository {

    /**
     * Оформить заказ по магазину. Возвращается только идентификатор: ответ
     * `POST fashion/orders` описан перекрытой схемой, и читать из него что-то
     * кроме id — гадание.
     */
    suspend fun create(store: FashionCartStore, form: CheckoutForm): ApiResult<String>

    suspend fun myOrders(page: Int = 0, size: Int = PAGE_SIZE): ApiResult<FashionOrderPage>

    suspend fun order(orderId: String): ApiResult<Order>

    /**
     * Отмена. Тело ответа не разбирается — новое состояние вызывающий
     * перечитывает [order]'ом: иначе неудачный разбор выглядел бы как
     * «отменить не удалось», хотя заказ уже отменён.
     */
    suspend fun cancel(orderId: String): ApiResult<Unit>

    companion object {
        const val PAGE_SIZE = 20
    }
}

@Singleton
class DefaultFashionOrderRepository @Inject constructor(
    private val api: FashionApi,
) : FashionOrderRepository {

    /**
     * `itemId` строки заказа — это **`variantId`**, а не id товара: в корзине
     * бэкенда строка ключуется вариантом, и заказывают конкретный размер
     * конкретного цвета. Проверить это живым запросом нельзя (`401` приходит
     * до валидации тела), поэтому отправляемое тело закреплено тестом.
     */
    override suspend fun create(
        store: FashionCartStore,
        form: CheckoutForm,
    ): ApiResult<String> {
        // Пустой заказ до сети не доходит: 400 сказал бы то же самое, но
        // платой были бы запрос и спиннер.
        if (store.items.isEmpty()) return ApiResult.Failure(ApiError.Business(EMPTY_ORDER_CODE))

        val result = apiCall {
            api.createOrder(
                PlaceOrderRequestDto(
                    placeId = store.storeId,
                    items = store.items.map { item ->
                        OrderItemRequestDto(itemId = item.variantId, quantity = item.quantity)
                    },
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
                // Заказ создан, но идентификатора в ответе нет. В отличие от
                // «Еды», где экран статуса без id показать нечем, здесь это не
                // отказ: заказ найдётся в «моих заказах» — их и открывает
                // экран подтверждения.
                    ?: return ApiResult.Success("")
                ApiResult.Success(orderId)
            }
        }
    }

    override suspend fun myOrders(page: Int, size: Int): ApiResult<FashionOrderPage> = apiCall {
        val response = api.myOrders(
            vertical = CLOTHING_VERTICAL,
            page = page.coerceAtLeast(0),
            size = size,
        ).payload()
        FashionOrderPage(
            items = response.content.mapNotNull { it.toDomain() },
            hasMore = response.hasMore(page),
        )
    }

    override suspend fun order(orderId: String): ApiResult<Order> {
        return when (val result = apiCall { api.order(orderId).payload() }) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> result.data.toDomain()
                ?.let { ApiResult.Success(it) }
                ?: ApiResult.Failure(ApiError.Serialization)
        }
    }

    override suspend fun cancel(orderId: String): ApiResult<Unit> = apiCall {
        api.cancelOrder(orderId).ensureSuccess()
    }

    private companion object {
        /** Заказ без единой строки — ошибка экрана, а не сервера. */
        const val EMPTY_ORDER_CODE = "FASHION_ORDER_EMPTY"
    }
}

/**
 * Есть ли следующая страница. Приоритет у `last` — его считает сервер; без
 * него смотрим на номер страницы и `totalPages`. Полное молчание о страницах
 * останавливает догрузку: лучше не показать хвост, чем крутить одну страницу
 * в цикле.
 *
 * [requestedPage] — то, что попросили мы: сервер, не вернувший `page`, отдаёт
 * дефолтный `0`, и «следующей» навсегда осталась бы первая.
 */
private fun OrderPageDto.hasMore(requestedPage: Int): Boolean {
    last?.let { return !it }
    val total = totalPages ?: return false
    return requestedPage + 1 < total
}
