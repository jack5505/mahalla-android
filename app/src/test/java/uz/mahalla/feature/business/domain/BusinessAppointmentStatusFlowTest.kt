package uz.mahalla.feature.business.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.feature.booking.domain.AppointmentStatus
import uz.mahalla.feature.booking.domain.AppointmentVertical

/**
 * Переходы статуса записи со стороны заведения (issue #289) — журнал
 * барбершопа и клиники.
 */
class BusinessAppointmentStatusFlowTest {

    @Test
    fun `a pending appointment is either confirmed or rejected`() {
        assertEquals(
            listOf(AppointmentStatus.Confirmed, AppointmentStatus.Cancelled),
            BusinessAppointmentStatusFlow.nextStatuses(AppointmentStatus.Pending, AppointmentVertical.Barber),
        )
    }

    /** У барбершопа есть «не пришёл» — у бэкенда `AppointmentBookingResponse` его знает. */
    @Test
    fun `a confirmed barber appointment can be marked as a no-show`() {
        assertEquals(
            listOf(AppointmentStatus.Completed, AppointmentStatus.NoShow, AppointmentStatus.Cancelled),
            BusinessAppointmentStatusFlow.nextStatuses(AppointmentStatus.Confirmed, AppointmentVertical.Barber),
        )
    }

    /** У клиники «не пришёл» нет — `HospitalAppointmentResponse` такого статуса не знает. */
    @Test
    fun `a confirmed doctor appointment offers no no-show`() {
        val next = BusinessAppointmentStatusFlow.nextStatuses(
            AppointmentStatus.Confirmed,
            AppointmentVertical.Doctor,
        )

        assertEquals(listOf(AppointmentStatus.Completed, AppointmentStatus.Cancelled), next)
        assertFalse(
            BusinessAppointmentStatusFlow.isAllowed(
                AppointmentStatus.Confirmed,
                AppointmentStatus.NoShow,
                AppointmentVertical.Doctor,
            ),
        )
    }

    @Test
    fun `a step cannot be skipped`() {
        assertFalse(
            BusinessAppointmentStatusFlow.isAllowed(
                AppointmentStatus.Pending,
                AppointmentStatus.Completed,
                AppointmentVertical.Barber,
            ),
        )
    }

    @Test
    fun `a finished appointment offers nothing`() {
        listOf(AppointmentStatus.Completed, AppointmentStatus.Cancelled, AppointmentStatus.NoShow).forEach {
            assertTrue(
                BusinessAppointmentStatusFlow.nextStatuses(it, AppointmentVertical.Barber).isEmpty(),
            )
            assertTrue(BusinessAppointmentStatusFlow.isFinal(it))
        }
    }

    /** Незнакомый статус — «неизвестно, чем кончилось», предлагать переход по нему нельзя. */
    @Test
    fun `an unknown status offers nothing`() {
        assertTrue(
            BusinessAppointmentStatusFlow.nextStatuses(
                AppointmentStatus.Unknown,
                AppointmentVertical.Barber,
            ).isEmpty(),
        )
        assertFalse(BusinessAppointmentStatusFlow.isFinal(AppointmentStatus.Unknown))
    }

    @Test
    fun `only a pending appointment is waiting for an answer`() {
        assertTrue(BusinessAppointmentStatusFlow.isPending(AppointmentStatus.Pending))
        assertFalse(BusinessAppointmentStatusFlow.isPending(AppointmentStatus.Confirmed))
    }
}
