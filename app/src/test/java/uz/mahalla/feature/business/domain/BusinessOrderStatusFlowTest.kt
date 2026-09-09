package uz.mahalla.feature.business.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.feature.food.domain.DeliveryMethod
import uz.mahalla.feature.food.domain.OrderStatus

/**
 * Переходы статуса заказа со стороны заведения (задача 12.3).
 *
 * Главное здесь — что назад дороги нет и что шаг нельзя перепрыгнуть: клиент
 * уже увидел «готов», и возврат в «готовится» ломает не данные, а обещание.
 */
class BusinessOrderStatusFlowTest {

    @Test
    fun `a new order is either accepted or rejected`() {
        assertEquals(
            listOf(OrderStatus.Confirmed, OrderStatus.Cancelled),
            BusinessOrderStatusFlow.nextStatuses(OrderStatus.Created, DeliveryMethod.Delivery),
        )
    }

    @Test
    fun `an accepted order goes to preparing`() {
        assertEquals(
            listOf(OrderStatus.Preparing, OrderStatus.Cancelled),
            BusinessOrderStatusFlow.nextStatuses(OrderStatus.Confirmed, DeliveryMethod.Pickup),
        )
    }

    /** После начала готовки продукты потрачены — отмена уходит из кнопок. */
    @Test
    fun `a preparing order can no longer be cancelled`() {
        val next = BusinessOrderStatusFlow.nextStatuses(
            OrderStatus.Preparing,
            DeliveryMethod.Delivery,
        )

        assertEquals(listOf(OrderStatus.ReadyForPickup), next)
        assertFalse(
            BusinessOrderStatusFlow.isAllowed(
                OrderStatus.Preparing,
                OrderStatus.Cancelled,
                DeliveryMethod.Delivery,
            ),
        )
    }

    @Test
    fun `a ready delivery goes to the courier, a ready pickup is handed over at once`() {
        assertEquals(
            listOf(OrderStatus.Delivering),
            BusinessOrderStatusFlow.nextStatuses(
                OrderStatus.ReadyForPickup,
                DeliveryMethod.Delivery,
            ),
        )
        assertEquals(
            listOf(OrderStatus.Completed),
            BusinessOrderStatusFlow.nextStatuses(
                OrderStatus.ReadyForPickup,
                DeliveryMethod.Pickup,
            ),
        )
    }

    @Test
    fun `a step cannot be skipped`() {
        assertFalse(
            BusinessOrderStatusFlow.isAllowed(
                OrderStatus.Created,
                OrderStatus.ReadyForPickup,
                DeliveryMethod.Pickup,
            ),
        )
    }

    @Test
    fun `there is no way back`() {
        assertFalse(
            BusinessOrderStatusFlow.isAllowed(
                OrderStatus.ReadyForPickup,
                OrderStatus.Preparing,
                DeliveryMethod.Pickup,
            ),
        )
    }

    @Test
    fun `a finished order offers nothing`() {
        listOf(OrderStatus.Completed, OrderStatus.Cancelled, OrderStatus.Refunded).forEach {
            assertTrue(
                BusinessOrderStatusFlow.nextStatuses(it, DeliveryMethod.Delivery).isEmpty(),
            )
            assertTrue(BusinessOrderStatusFlow.isFinal(it))
        }
    }

    /**
     * Незнакомый статус — это «неизвестно, где заказ». Предлагать по нему
     * переход значит угадывать за сервер.
     */
    @Test
    fun `an unknown status offers nothing`() {
        assertTrue(
            BusinessOrderStatusFlow.nextStatuses(
                OrderStatus.Unknown,
                DeliveryMethod.Delivery,
            ).isEmpty(),
        )
        assertFalse(BusinessOrderStatusFlow.isFinal(OrderStatus.Unknown))
    }

    @Test
    fun `only a just-created order counts as new`() {
        assertTrue(BusinessOrderStatusFlow.isNew(OrderStatus.Created))
        assertFalse(BusinessOrderStatusFlow.isNew(OrderStatus.Confirmed))
    }

    @Test
    fun `the all filter sends no status at all`() {
        assertEquals(null, BusinessOrderFilter.All.apiValue)
        assertEquals("NEW", BusinessOrderFilter.New.apiValue)
        assertEquals("READY", BusinessOrderFilter.Ready.apiValue)
    }
}
