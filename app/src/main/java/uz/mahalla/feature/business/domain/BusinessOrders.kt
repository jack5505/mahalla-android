package uz.mahalla.feature.business.domain

import uz.mahalla.feature.discovery.domain.PlaceCategory
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
 * Приезжает из `GET food/places/{placeId}/orders` («Еда», схема
 * `FoodOrderResponse`) или `GET fashion/stores/{storeId}/orders` («Одежда»,
 * `FashionOrderResponse`, issue #187) — модель общая, разные вертикали
 * маппятся в неё каждая своим DTO (`BusinessMappers.kt`). Это не `OrderView`,
 * который читает клиент (`orders/{orderId}`): полей почти столько же, но
 * заведению виден состав сразу в списке, а имени заведения нет — оно и так
 * своё.
 *
 * @param number номер для человека («F-2026-0042»): его называют по телефону.
 * @param vertical чей это заказ (issue #289). У единой ленты
 * (`GET places/{placeId}/orders`) один запрос отдаёт заказы всех вертикалей
 * сразу, и по этому полю экран решает, можно ли вообще менять статус —
 * см. [BusinessOrderStatusFlow.canChangeStatus].
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
    val vertical: PlaceCategory = PlaceCategory.Food,
)

/**
 * `vertical` заказа бэкенд называет не так, как каталог: `CLOTHING`, а не
 * `FASHION` (см. `PlaceCategory.Fashion` KDoc), `GAMING` — как в каталоге.
 * [PlaceCategory.fromApi] эти написания уже понимает через алиасы (разбор
 * ответа), а в обратную сторону — фильтр запроса — нужна своя таблица: у
 * [PlaceCategory.apiValue] так, как их называет каталог, а не заказы.
 *
 * Единственный источник правды о том, у каких вертикалей вообще бывают
 * заказы в единой ленте (issue #289) — [BUSINESS_ORDER_VERTICALS] выведен из
 * тех же ключей, а не собран отдельным списком: разошедшиеся копии одного и
 * того же перечня — грабли сами по себе.
 */
private val ORDER_VERTICAL_API_VALUES: Map<PlaceCategory, String> = mapOf(
    PlaceCategory.Food to "FOOD",
    PlaceCategory.Fashion to "CLOTHING",
    PlaceCategory.Pharmacy to "PHARMACY",
    PlaceCategory.Cinema to "CINEMA",
    PlaceCategory.Playground to "GAMING",
)

/** Что уходит в query-параметр `vertical`; `null` — параметр не отправляется. */
fun PlaceCategory.orderVerticalApiValue(): String? = ORDER_VERTICAL_API_VALUES[this]

/**
 * Вертикали, у которых бывают заказы в единой ленте (issue #289) —
 * `OrderView.vertical` бэкенда: `FOOD`, `CLOTHING`, `PHARMACY`, `CINEMA`,
 * `GAMING`. Мастер и больница сюда не входят: у записи на приём заказов не
 * бывает вовсе, это другая сущность — см. журнал (`BusinessJournal.kt`).
 */
val BUSINESS_ORDER_VERTICALS: List<PlaceCategory> = ORDER_VERTICAL_API_VALUES.keys.toList()

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

    /**
     * Есть ли у заведения ручка, которая вообще меняет статус этого заказа
     * (issue #289). Единая лента показывает заказы всех вертикалей, а
     * `PUT .../orders/{id}/status` существует только у «Еды» и «Одежды» —
     * `food/places/{id}/orders/{id}/status` и
     * `fashion/stores/{id}/orders/{id}/status` (issue #187). У аптеки, кино и
     * игровой зоны такой ручки у бэкенда нет вовсе: аптека заказов не
     * принимает, а билет и бронь снимаются своими путями (`cancel`,
     * `complete`), не общей сменой статуса. Кнопки поэтому не рисуются —
     * предложить их значило бы получить `404`/`405` там, где приложение могло
     * знать заранее.
     */
    fun canChangeStatus(vertical: PlaceCategory): Boolean =
        vertical == PlaceCategory.Food || vertical == PlaceCategory.Fashion
}

/**
 * Фильтр списка входящих заказов по статусу.
 *
 * `GET places/{placeId}/orders` (issue #289) принимает `status` — одно
 * значение, не список. Поэтому фильтр здесь ровно такой же: одна вкладка —
 * один запрос, а «все» — запрос без параметра. Собирать «активные» из
 * четырёх запросов клиентом значило бы четыре раза пагинировать и склеивать
 * страницы вручную.
 *
 * Второй, независимый фильтр — по вертикали ([BUSINESS_ORDER_VERTICALS]), он
 * не enum, а обычный `PlaceCategory?`: значений пять, и заводить под них
 * второе перечисление ради единственного метода [orderVerticalApiValue]
 * незачем.
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
