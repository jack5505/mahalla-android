package uz.mahalla.feature.food.domain

import java.time.Instant
import java.util.Locale

/**
 * Статус заказа (эпик 5.4). Значения — перечисление бэкенда `OrderStatus`
 * (снято со стенда: `NEW`, `ACCEPTED`, `PREPARING`, `READY`, `IN_DELIVERY`,
 * `DELIVERED`, `CANCELLED`, `REFUNDED`).
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
    Refunded,
    Unknown,
    ;

    val apiValue: String
        get() = when (this) {
            Created -> "NEW"
            Confirmed -> "ACCEPTED"
            Preparing -> "PREPARING"
            ReadyForPickup -> "READY"
            Delivering -> "IN_DELIVERY"
            Completed -> "DELIVERED"
            Cancelled -> "CANCELLED"
            Refunded -> "REFUNDED"
            Unknown -> "UNKNOWN"
        }

    companion object {
        fun fromApi(value: String?): OrderStatus {
            val normalized = value?.trim()?.uppercase(Locale.ROOT)?.replace('-', '_')
                ?: return Unknown
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
    /**
     * Название заведения. Приходит не из ответа о заказе (в `OrderView` его
     * нет), а из кэша заказов: имя знала корзина, из которой заказ оформили.
     */
    val placeName: String,
    /** Номер заказа для человека («F-2026-0042»); его называют в поддержке. */
    val number: String? = null,
    val status: OrderStatus,
    val method: DeliveryMethod,
    val payment: PaymentMethod,
    val totals: CartTotals,
    val lines: List<CartLine> = emptyList(),
    /** Когда заказ создан; `null` — сервер не прислал дату или прислал битую. */
    val createdAt: Instant?,
    val address: String? = null,
)

/**
 * Переходы статусов (эпик 5.4).
 *
 * Этапы зависят от способа получения: у самовывоза нет доставки. Рисовать
 * общий список с вечно серым лишним этапом — значит показывать человеку шаг,
 * который никогда не наступит.
 */
object OrderStatusFlow {

    /**
     * «Готово» есть в обеих цепочках: у бэкенда это один общий `READY` и для
     * доставки (блюда собраны, ждут курьера), и для самовывоза. Выбросить его
     * из доставки значит однажды получить статус, которого нет в цепочке, — и
     * не нарисовать прогресс вовсе.
     */
    private val DELIVERY_STAGES = listOf(
        OrderStatus.Created,
        OrderStatus.Confirmed,
        OrderStatus.Preparing,
        OrderStatus.ReadyForPickup,
        OrderStatus.Delivering,
        OrderStatus.Completed,
    )

    /** У самовывоза нет доставки — вечно серый лишний этап рисовать незачем. */
    private val PICKUP_STAGES = listOf(
        OrderStatus.Created,
        OrderStatus.Confirmed,
        OrderStatus.Preparing,
        OrderStatus.ReadyForPickup,
        OrderStatus.Completed,
    )

    fun stages(method: DeliveryMethod): List<OrderStatus> =
        if (method == DeliveryMethod.Delivery) DELIVERY_STAGES else PICKUP_STAGES

    /** Дальше статус не изменится — опрос сервера можно прекращать. */
    fun isFinal(status: OrderStatus): Boolean = status == OrderStatus.Completed ||
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
