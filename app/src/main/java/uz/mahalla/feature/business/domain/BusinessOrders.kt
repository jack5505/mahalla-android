package uz.mahalla.feature.business.domain

import uz.mahalla.feature.food.domain.DeliveryMethod
import uz.mahalla.feature.food.domain.OrderStatus
import uz.mahalla.feature.food.domain.PaymentMethod
import java.time.Instant

/** Строка входящего заказа: что и сколько заказали. */
data class BusinessOrderLine(
    val itemId: String,
    val name: String,
    val quantity: Int,
    val unitPriceSum: Long,
    val totalPriceSum: Long,
)

/**
 * Входящий заказ **глазами заведения** (задача 12.3).
 *
 * Приезжает из `GET food/places/{placeId}/orders` — схема `FoodOrderResponse`.
 * Это не `OrderView`, который читает клиент (`orders/{orderId}`): полей почти
 * столько же, но заведению видны `staffId` и состав сразу в списке, а имени
 * заведения нет — оно и так своё.
 *
 * @param number номер для человека («F-2026-0042»): его называют по телефону.
 */
data class BusinessOrder(
    val id: String,
    val number: String? = null,
    val status: OrderStatus,
    val method: DeliveryMethod,
    val payment: PaymentMethod,
    val itemsSum: Long = 0,
    val deliverySum: Long = 0,
    val discountSum: Long = 0,
    val totalSum: Long = 0,
    val address: String? = null,
    val lines: List<BusinessOrderLine> = emptyList(),
    val createdAt: Instant? = null,
)

/**
 * Страница входящих заказов.
 *
 * @param hasMore считается ровно так же, как у «моих заведений» (issue #94):
 * по `last`, иначе по `page`/`totalPages`, иначе «хватит» — лучше не показать
 * хвост, чем зациклить догрузку одной и той же страницы.
 */
data class BusinessOrderPage(
    val items: List<BusinessOrder> = emptyList(),
    val hasMore: Boolean = false,
)

/**
 * Переходы статуса заказа **со стороны заведения** (задача 12.3).
 *
 * Клиентский `OrderStatusFlow` рисует цепочку целиком, включая этапы, которые
 * ещё не наступили. Здесь другое: какие статусы заведение может выставить
 * **прямо сейчас**, — и это не то же самое, что «следующий этап цепочки».
 *
 * Правило одно: вперёд ровно на шаг, назад — никогда. Заказ, вернувшийся из
 * «готов» в «готовится», клиент уже увидел готовым, и его телефон об этом
 * сообщил; откат ломает не данные, а обещание.
 */
object BusinessOrderStatusFlow {

    /**
     * Что можно выставить из текущего статуса. Порядок — порядок кнопок:
     * сначала движение вперёд, отмена последней.
     *
     * `READY` расходится по способу получения: доставку забирает курьер
     * (`IN_DELIVERY`), самовывоз человек уносит сам — и заказ сразу
     * `DELIVERED`. Показать самовывозу «в пути» значит предложить этап,
     * которого не будет.
     *
     * Отмена доступна только до начала готовки — то же правило, что у
     * клиента (`OrderStatusFlow.canCancel`): после этого продукты потрачены, и
     * отмена перестаёт быть кнопкой. Возврата (`REFUNDED`) в списке нет вовсе:
     * деньги двигает платёжный контур, а не кухня.
     *
     * [OrderStatus.Unknown] не даёт ничего: незнакомый статус — это
     * «неизвестно, где заказ», и предлагать по нему переход значит угадывать.
     */
    fun nextStatuses(status: OrderStatus, method: DeliveryMethod): List<OrderStatus> =
        when (status) {
            OrderStatus.Created -> listOf(OrderStatus.Confirmed, OrderStatus.Cancelled)
            OrderStatus.Confirmed -> listOf(OrderStatus.Preparing, OrderStatus.Cancelled)
            OrderStatus.Preparing -> listOf(OrderStatus.ReadyForPickup)
            OrderStatus.ReadyForPickup -> if (method == DeliveryMethod.Delivery) {
                listOf(OrderStatus.Delivering)
            } else {
                listOf(OrderStatus.Completed)
            }

            OrderStatus.Delivering -> listOf(OrderStatus.Completed)

            OrderStatus.Completed, OrderStatus.Cancelled, OrderStatus.Refunded,
            OrderStatus.Unknown,
            -> emptyList()
        }

    fun isAllowed(from: OrderStatus, to: OrderStatus, method: DeliveryMethod): Boolean =
        to in nextStatuses(from, method)

    /** Дальше заведение с заказом ничего не делает. */
    fun isFinal(status: OrderStatus): Boolean = status == OrderStatus.Completed ||
        status == OrderStatus.Cancelled ||
        status == OrderStatus.Refunded

    /**
     * «Новый» — тот, что ещё никто не подтвердил. Именно их считает бейдж на
     * дашборде и именно ради них экран перечитывается на возврате.
     */
    fun isNew(status: OrderStatus): Boolean = status == OrderStatus.Created
}

/**
 * Фильтр списка входящих заказов.
 *
 * `GET food/places/{placeId}/orders` принимает `status` — одно значение, не
 * список. Поэтому фильтр здесь ровно такой же: одна вкладка — один запрос, а
 * «все» — запрос без параметра. Собирать «активные» из четырёх запросов
 * клиентом значило бы четыре раза пагинировать и склеивать страницы вручную.
 */
enum class BusinessOrderFilter(val status: OrderStatus?) {
    All(null),
    New(OrderStatus.Created),
    Accepted(OrderStatus.Confirmed),
    Preparing(OrderStatus.Preparing),
    Ready(OrderStatus.ReadyForPickup),
    ;

    /** Что уходит в query-параметр; `null` — параметр не отправляется вовсе. */
    val apiValue: String? get() = status?.apiValue
}
