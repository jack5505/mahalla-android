package uz.mahalla.feature.services.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Статусы заявки на услугу (issue #71, схема `Response` контроллера walk-in). */
class ServiceRequestStatusTest {

    @Test
    fun `known statuses are parsed`() {
        assertEquals(ServiceRequestStatus.Pending, ServiceRequestStatus.fromServer("PENDING"))
        assertEquals(
            ServiceRequestStatus.CounterOffered,
            ServiceRequestStatus.fromServer("COUNTER_OFFERED"),
        )
        assertEquals(ServiceRequestStatus.InChair, ServiceRequestStatus.fromServer(" in_chair "))
    }

    @Test
    fun `unknown status does not hide the request`() {
        // Новый статус бэкенда должен читаться как «заявка в работе», а не
        // прятать её и не притворяться отказом.
        val status = ServiceRequestStatus.fromServer("ON_THE_WAY")

        assertEquals(ServiceRequestStatus.Unknown, status)
        assertFalse(status.isFinal)
        assertFalse(status.isRejected)
    }

    @Test
    fun `missing status is unknown too`() {
        assertEquals(ServiceRequestStatus.Unknown, ServiceRequestStatus.fromServer(null))
        assertEquals(ServiceRequestStatus.Unknown, ServiceRequestStatus.fromServer("  "))
    }

    @Test
    fun `final statuses are final`() {
        listOf(
            ServiceRequestStatus.Completed,
            ServiceRequestStatus.Cancelled,
            ServiceRequestStatus.Declined,
            ServiceRequestStatus.NoShow,
            ServiceRequestStatus.Expired,
        ).forEach { assertTrue(it.name, it.isFinal) }

        listOf(
            ServiceRequestStatus.Pending,
            ServiceRequestStatus.Accepted,
            ServiceRequestStatus.Waiting,
            ServiceRequestStatus.InChair,
            ServiceRequestStatus.CounterOffered,
        ).forEach { assertFalse(it.name, it.isFinal) }
    }

    @Test
    fun `rejected statuses are the ones worth a new request`() {
        // Кнопка «заказать ещё раз» рисуется по этому признаку: пока мастер
        // думает, второй такой же заявкой ему мешать незачем.
        assertTrue(ServiceRequestStatus.Declined.isRejected)
        assertTrue(ServiceRequestStatus.Expired.isRejected)
        assertTrue(ServiceRequestStatus.NoShow.isRejected)
        assertFalse(ServiceRequestStatus.Completed.isRejected)
        assertFalse(ServiceRequestStatus.Waiting.isRejected)
    }
}
