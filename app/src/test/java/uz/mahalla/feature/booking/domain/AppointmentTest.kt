package uz.mahalla.feature.booking.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Запись на время (issue #97): состояния, отмена и деление на разделы. */
class AppointmentTest {

    @Test
    fun `statuses of the backend are recognised in any notation`() {
        assertEquals(AppointmentStatus.Pending, AppointmentStatus.fromApi("PENDING"))
        assertEquals(AppointmentStatus.Confirmed, AppointmentStatus.fromApi(" confirmed "))
        assertEquals(AppointmentStatus.NoShow, AppointmentStatus.fromApi("no-show"))
        assertEquals(AppointmentStatus.Cancelled, AppointmentStatus.fromApi("CANCELLED"))
        assertEquals(AppointmentStatus.Completed, AppointmentStatus.fromApi("COMPLETED"))
    }

    @Test
    fun `an unfamiliar status hides nothing`() {
        val status = AppointmentStatus.fromApi("RESCHEDULED")

        assertEquals(AppointmentStatus.Unknown, status)
        // «Неизвестно, чем кончилось» — не «кончилось»: закрытой такую запись
        // объявлять нельзя, иначе исчезнет и кнопка отмены.
        assertFalse(appointment(status = status).isFinal)
        assertTrue(appointment(status = status).canCancel)
        assertEquals(AppointmentStatus.Unknown, AppointmentStatus.fromApi(null))
        assertEquals(AppointmentStatus.Unknown, AppointmentStatus.fromApi(" "))
    }

    @Test
    fun `finished appointments are not cancelled twice`() {
        listOf(
            AppointmentStatus.Cancelled,
            AppointmentStatus.Completed,
            AppointmentStatus.NoShow,
        ).forEach { status ->
            val appointment = appointment(status = status)
            assertTrue(status.name, appointment.isFinal)
            assertFalse(status.name, appointment.canCancel)
        }

        listOf(AppointmentStatus.Pending, AppointmentStatus.Confirmed).forEach { status ->
            assertTrue(status.name, appointment(status = status).canCancel)
        }
    }

    @Test
    fun `a record without an id cannot be cancelled`() {
        // Так выглядит ответ `POST appointments` без `id`: запись создана, но
        // отменять её нечем — кнопки быть не должно.
        assertFalse(appointment(id = "").canCancel)
    }

    /**
     * Отмена прошедшей записи не запрещена: `PENDING`, до которого заведение
     * так и не дошло, человек вправе снять — последнее слово за сервером.
     */
    @Test
    fun `a passed but unfinished appointment can still be cancelled`() {
        val passed = appointment(date = LocalDate.of(2026, 9, 1))

        assertTrue(passed.canCancel)
        assertFalse(passed.isUpcoming(NOW))
    }

    @Test
    fun `an appointment without a time is counted as upcoming`() {
        // Спрятать незакрытую запись в «прошедшие» значит спрятать и отмену.
        val undated = appointment(date = null, startTime = null)

        assertNull(undated.startsAt())
        assertTrue(undated.isUpcoming(NOW))
    }

    @Test
    fun `the start of an appointment is a moment in the zone of the place`() {
        val appointment = appointment(
            date = LocalDate.of(2026, 9, 5),
            startTime = LocalTime.of(10, 0),
        )

        assertEquals(Instant.parse("2026-09-05T05:00:00Z"), appointment.startsAt())
    }

    @Test
    fun `sections put the nearest appointment first and the freshest past on top`() {
        val soon = appointment(id = "soon", date = LocalDate.of(2026, 9, 5))
        val later = appointment(id = "later", date = LocalDate.of(2026, 9, 9))
        val undated = appointment(id = "undated", date = null, startTime = null)
        val yesterday = appointment(
            id = "yesterday",
            date = LocalDate.of(2026, 9, 3),
            status = AppointmentStatus.Completed,
        )
        val lastWeek = appointment(
            id = "last-week",
            date = LocalDate.of(2026, 8, 28),
            status = AppointmentStatus.Cancelled,
        )

        val split = AppointmentSections.split(
            listOf(later, yesterday, undated, lastWeek, soon),
            NOW,
        )

        assertEquals(listOf("soon", "later", "undated"), split.upcoming.map(Appointment::id))
        assertEquals(listOf("yesterday", "last-week"), split.past.map(Appointment::id))
        assertFalse(split.isEmpty)
    }

    @Test
    fun `a cancelled appointment of tomorrow belongs to the past`() {
        // «Активные» — это не «не отменённые»: отменённая запись на завтра
        // никуда не зовёт, и держать её наверху значит обещать визит.
        val cancelled = appointment(
            date = LocalDate.of(2026, 9, 5),
            status = AppointmentStatus.Cancelled,
        )

        val split = AppointmentSections.split(listOf(cancelled), NOW)

        assertTrue(split.upcoming.isEmpty())
        assertEquals(listOf(cancelled), split.past)
    }

    @Test
    fun `an empty list is an empty split`() {
        assertTrue(AppointmentSections.split(emptyList(), NOW).isEmpty)
    }

    private fun appointment(
        id: String = "a-1",
        date: LocalDate? = LocalDate.of(2026, 9, 5),
        startTime: LocalTime? = LocalTime.of(10, 0),
        status: AppointmentStatus = AppointmentStatus.Pending,
    ) = Appointment(
        id = id,
        date = date,
        startTime = startTime,
        status = status,
    )

    private companion object {
        /** 09:00 UTC = 14:00 в Ташкенте, 4 сентября. */
        val NOW: Instant = Instant.parse("2026-09-04T09:00:00Z")
    }
}
