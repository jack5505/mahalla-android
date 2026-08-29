package uz.mahalla.feature.food.domain

import java.time.Instant

/**
 * Статус заказа (эпик 5.4).
 *
 * [Unknown] обязателен: набор статусов задаёт бэкенд, и новый статус не должен
 * ронять экран заказа — он покажет его как «в работе».
 */
enum class OrderStatus {
    Created,
    Confirmed,
    Preparing,
    Delivering,
    ReadyForPickup,
    Completed,
    Cancelled,
    Unknown,
    ;

    val apiValue: String
        get() = when (this) {
            Created -> "created"
            Confirmed -> "confirmed"
            Preparing -> "preparing"
            Delivering -> "delivering"
            ReadyForPickup -> "ready_for_pickup"
            Completed -> "completed"
            Cancelled -> "cancelled"
            Unknown -> "unknown"
        }

    companion object {
        fun fromApi(value: String?): OrderStatus {
            val normalized = value?.trim()?.lowercase()?.replace('-', '_') ?: return Unknown
            return entries.firstOrNull { it.apiValue == normalized } ?: Unknown
        }
    }
}

/**
 * Заказ. [lines] — снимок корзины на момент оформления: меню поменяется, а чек
 * должен остаться прежним.
 */
data class Order(
    val id: String,
    val placeId: String,
    val placeName: String,
    val status: OrderStatus,
    val method: DeliveryMethod,
    val payment: PaymentMethod,
    val totals: CartTotals,
    val lines: List<CartLine> = emptyList(),
    val createdAt: Instant,
    val address: String? = null,
    val comment: String? = null,
    /** Оценка готовности в минутах; `null` — сервер её не прислал. */
    val etaMinutes: Int? = null,
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
        status == OrderStatus.Completed || status == OrderStatus.Cancelled

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
