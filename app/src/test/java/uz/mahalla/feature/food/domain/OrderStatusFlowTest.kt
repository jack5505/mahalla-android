package uz.mahalla.feature.food.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Переходы статусов заказа (эпик 5.4). */
class OrderStatusFlowTest {

    @Test
    fun `only delivery has the on-the-way stage`() {
        val delivery = OrderStatusFlow.stages(DeliveryMethod.Delivery)
        val pickup = OrderStatusFlow.stages(DeliveryMethod.Pickup)

        // Лишний вечно серый этап — это шаг, который никогда не наступит.
        assertTrue(delivery.contains(OrderStatus.Delivering))
        assertFalse(pickup.contains(OrderStatus.Delivering))
        // А «готово» бэкенд присылает и там и там — одним общим `READY`.
        assertTrue(delivery.contains(OrderStatus.ReadyForPickup))
        assertTrue(pickup.contains(OrderStatus.ReadyForPickup))
    }

    @Test
    fun `every status of the backend chain is drawn for its method`() {
        // Статус, которого нет в цепочке, оставляет экран без прогресса.
        listOf(
            OrderStatus.Created,
            OrderStatus.Confirmed,
            OrderStatus.Preparing,
            OrderStatus.ReadyForPickup,
            OrderStatus.Delivering,
            OrderStatus.Completed,
        ).forEach { status ->
            assertTrue(
                status.name,
                OrderStatusFlow.stageIndex(status, DeliveryMethod.Delivery) >= 0,
            )
        }
    }

    @Test
    fun `final statuses stop the polling`() {
        assertTrue(OrderStatusFlow.isFinal(OrderStatus.Completed))
        assertTrue(OrderStatusFlow.isFinal(OrderStatus.Cancelled))
        // Возврат денег — тоже конец истории заказа.
        assertTrue(OrderStatusFlow.isFinal(OrderStatus.Refunded))
        assertFalse(OrderStatusFlow.isFinal(OrderStatus.Preparing))
    }

    @Test
    fun `cancelling is allowed only before cooking starts`() {
        assertTrue(OrderStatusFlow.canCancel(OrderStatus.Created))
        assertTrue(OrderStatusFlow.canCancel(OrderStatus.Confirmed))
        assertFalse(OrderStatusFlow.canCancel(OrderStatus.Preparing))
        assertFalse(OrderStatusFlow.canCancel(OrderStatus.Delivering))
        assertFalse(OrderStatusFlow.canCancel(OrderStatus.Completed))
    }

    @Test
    fun `repeating is offered for finished and cancelled orders`() {
        assertTrue(OrderStatusFlow.canRepeat(OrderStatus.Completed))
        assertTrue(OrderStatusFlow.canRepeat(OrderStatus.Cancelled))
        assertFalse(OrderStatusFlow.canRepeat(OrderStatus.Preparing))
    }

    @Test
    fun `passed stages are marked done and the current one is not`() {
        val method = DeliveryMethod.Delivery

        assertTrue(OrderStatusFlow.isStageDone(OrderStatus.Created, OrderStatus.Preparing, method))
        assertTrue(OrderStatusFlow.isStageDone(OrderStatus.Confirmed, OrderStatus.Preparing, method))
        assertFalse(OrderStatusFlow.isStageDone(OrderStatus.Preparing, OrderStatus.Preparing, method))
        assertFalse(OrderStatusFlow.isStageDone(OrderStatus.Delivering, OrderStatus.Preparing, method))
    }

    @Test
    fun `a cancelled order is outside the chain`() {
        assertEquals(-1, OrderStatusFlow.stageIndex(OrderStatus.Cancelled, DeliveryMethod.Delivery))
        assertFalse(
            OrderStatusFlow.isStageDone(
                OrderStatus.Created,
                OrderStatus.Cancelled,
                DeliveryMethod.Delivery,
            ),
        )
    }

    @Test
    fun `an unknown status from the server does not break the screen`() {
        assertEquals(OrderStatus.Unknown, OrderStatus.fromApi("teleported"))
        assertEquals(OrderStatus.Unknown, OrderStatus.fromApi(null))
        assertFalse(OrderStatusFlow.isFinal(OrderStatus.Unknown))
        assertFalse(OrderStatusFlow.canCancel(OrderStatus.Unknown))
    }

    @Test
    fun `status names survive the round trip through the api value`() {
        OrderStatus.entries.forEach { status ->
            assertEquals(status, OrderStatus.fromApi(status.apiValue))
        }
        // Значения — перечисление бэкенда, а не собственные названия.
        assertEquals(OrderStatus.Created, OrderStatus.fromApi("NEW"))
        assertEquals(OrderStatus.Confirmed, OrderStatus.fromApi("ACCEPTED"))
        assertEquals(OrderStatus.ReadyForPickup, OrderStatus.fromApi("READY"))
        assertEquals(OrderStatus.Delivering, OrderStatus.fromApi("IN_DELIVERY"))
        assertEquals(OrderStatus.Completed, OrderStatus.fromApi("DELIVERED"))
        assertEquals(OrderStatus.Refunded, OrderStatus.fromApi("REFUNDED"))
        // Регистр и дефис вместо подчёркивания — частая разница в контрактах.
        assertEquals(OrderStatus.Delivering, OrderStatus.fromApi("in-delivery"))
    }

    @Test
    fun `fulfillment values come from the backend enum`() {
        assertEquals(DeliveryMethod.Delivery, DeliveryMethod.fromApi("DELIVERY"))
        assertEquals(DeliveryMethod.Pickup, DeliveryMethod.fromApi("PICKUP"))
        // «На месте» — не доставка: адреса нет, курьера нет.
        assertEquals(DeliveryMethod.Pickup, DeliveryMethod.fromApi("DINE_IN"))
        // Незнакомое значение тоже не доставка: этап «в пути», которого не
        // будет, рисовать нельзя.
        assertEquals(DeliveryMethod.Pickup, DeliveryMethod.fromApi("teleport"))
        assertEquals(DeliveryMethod.Delivery, DeliveryMethod.fromApi("delivery"))
    }

    @Test
    fun `payment values come from the backend enum`() {
        assertEquals(PaymentMethod.Wallet, PaymentMethod.fromApi("WALLET"))
        assertEquals(PaymentMethod.Cash, PaymentMethod.fromApi("CASH"))
        // Кошелёк — способ по умолчанию: наличными сервер объявит явно.
        assertEquals(PaymentMethod.Wallet, PaymentMethod.fromApi(null))
    }
}
