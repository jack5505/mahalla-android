package uz.mahalla.feature.food.domain

import java.time.Instant

/**
 * Статус заказа (эпик 5.4).
 *
 * Значения — перечисление бэкенда (issue #63): `NEW`, `ACCEPTED`, `PREPARING`,
 * `READY`, `IN_DELIVERY`, `DELIVERED`, `CANCELLED`, `REFUNDED`. [Unknown]
 * обязателен: набор задаёт сервер, и новый статус не должен ронять экран
 * заказа — он покажет его как «в работе».
 */
enum class OrderStatus {
    Created,
    Confirmed,
    Preparing,
    Delivering,
    ReadyForPickup,
    Completed,
    Cancelled,
    Refunded,
    Unknown,
    ;

    val apiValue: String
        get() = when (this) {
            Created -> "NEW"
            Confirmed -> "ACCEPTED"
            Preparing -> "PREPARING"
            Delivering -> "IN_DELIVERY"
            ReadyForPickup -> "READY"
            Completed -> "DELIVERED"
            Cancelled -> "CANCELLED"
            Refunded -> "REFUNDED"
            Unknown -> "UNKNOWN"
        }

    companion object {
        fun fromApi(value: String?): OrderStatus {
            val normalized = value?.trim()?.uppercase()?.replace('-', '_') ?: return Unknown
            return entries.firstOrNull { it.apiValue == normalized } ?: Unknown
        }
    }
}

/**
 * Заказ. [lines] — снимок корзины на момент оформления: меню поменяется, а чек
 * должен остаться прежним.
 *
 * [totalSum] приходит от бэкенда отдельным полем и не пересчитывается из
 * [totals]: платит человек по числу сервера, и собственная арифметика
 * приложения не имеет права с ним разойтись. [totals] остаётся ради разбивки
 * (позиции / скидка / доставка).
 *
 * [placeName] бэкенд в заказе не отдаёт — он подставляется из кэша мест, см.
 * `DefaultOrderRepository`.
 */
data class Order(
    val id: String,
    val placeId: String,
    val placeName: String,
    /** Номер для человека («A-1042»); пустой, если сервер его не прислал. */
    val orderNumber: String = "",
    val status: OrderStatus,
    val method: DeliveryMethod,
    val payment: PaymentMethod,
    val totals: CartTotals,
    val totalSum: Long,
    val lines: List<CartLine> = emptyList(),
    val createdAt: Instant,
    val address: String? = null,
)

/**
 * Переходы статусов (эпик 5.4).
 *
 * Этапы зависят от способа получения: у самовывоза нет доставки, а у доставки
 * нет «готово к выдаче». Рисовать общий список с вечно серым лишним этапом —
 * значит показывать человеку шаг, который никогда не наступит.
 */
object OrderStatusFlow {

    private val DELIVERY_STAGES = listOf(
        OrderStatus.Created,
        OrderStatus.Confirmed,
        OrderStatus.Preparing,
        OrderStatus.Delivering,
        OrderStatus.Completed,
    )

    private val PICKUP_STAGES = listOf(
        OrderStatus.Created,
        OrderStatus.Confirmed,
        OrderStatus.Preparing,
        OrderStatus.ReadyForPickup,
        OrderStatus.Completed,
    )

    fun stages(method: DeliveryMethod): List<OrderStatus> =
        if (method == DeliveryMethod.Pickup) PICKUP_STAGES else DELIVERY_STAGES

    /** Дальше статус не изменится — опрос сервера можно прекращать. */
    fun isFinal(status: OrderStatus): Boolean =
        status == OrderStatus.Completed ||
            status == OrderStatus.Cancelled ||
            status == OrderStatus.Refunded

    /**
     * Отменить можно, пока кухня не начала готовить: после этого продукты уже
     * потрачены, и отмена — разговор с заведением, а не кнопка в приложении.
     */
    fun canCancel(status: OrderStatus): Boolean =
        status == OrderStatus.Created || status == OrderStatus.Confirmed

    /** Повторить — у завершённого и у отменённого: оба уже не изменятся. */
    fun canRepeat(status: OrderStatus): Boolean = isFinal(status)

    /**
     * Номер текущего этапа в [stages]. `-1` — статуса нет в цепочке (отмена или
     * незнакомое значение): прогресс тогда не рисуется вовсе.
     */
    fun stageIndex(status: OrderStatus, method: DeliveryMethod): Int =
        stages(method).indexOf(status)

    /** Этап пройден — его отмечают галочкой, а не точкой. */
    fun isStageDone(stage: OrderStatus, status: OrderStatus, method: DeliveryMethod): Boolean {
        val current = stageIndex(status, method)
        if (current < 0) return false
        return stages(method).indexOf(stage) < current
    }
}
