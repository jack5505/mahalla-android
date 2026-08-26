package uz.mahalla.feature.food.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Переходы статусов заказа (эпик 5.4). */
class OrderStatusFlowTest {

    @Test
    fun `delivery and pickup have different stages`() {
        val delivery = OrderStatusFlow.stages(DeliveryMethod.Delivery)
        val pickup = OrderStatusFlow.stages(DeliveryMethod.Pickup)

        // Лишний вечно серый этап — это шаг, который никогда не наступит.
        assertTrue(delivery.contains(OrderStatus.Delivering))
        assertFalse(delivery.contains(OrderStatus.ReadyForPickup))
        assertTrue(pickup.contains(OrderStatus.ReadyForPickup))
        assertFalse(pickup.contains(OrderStatus.Delivering))
    }

    @Test
    fun `final statuses stop the polling`() {
        assertTrue(OrderStatusFlow.isFinal(OrderStatus.Completed))
        assertTrue(OrderStatusFlow.isFinal(OrderStatus.Cancelled))
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
        // Дефис вместо подчёркивания — частая разница в контрактах.
        assertEquals(OrderStatus.ReadyForPickup, OrderStatus.fromApi("READY-FOR-PICKUP"))
    }

    @Test
    fun `delivery method falls back to the stricter option`() {
        // Неизвестный способ требует адреса — лучше спросить лишнее, чем
        // отправить заказ в никуда.
        assertEquals(DeliveryMethod.Delivery, DeliveryMethod.fromApi("teleport"))
        assertEquals(DeliveryMethod.Pickup, DeliveryMethod.fromApi("PICKUP"))
    }
}
