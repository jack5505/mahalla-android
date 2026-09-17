package uz.mahalla.feature.booking.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Общая сетка времени для мастеров-фрилансеров (issue #107): бэкенд не
 * сообщает их занятость, и сетка строится на клиенте по тем же правилам, что
 * раньше использовала запись к врачу (issue #99) — до issue #181, когда у
 * больниц появилась настоящая ручка слотов и `DoctorSchedule` ушёл вместе со
 * своим тестом.
 *
 * Правило здесь одно, и оно про «уже прошло». Границы сетки при этом задаёт
 * вызывающий, и здесь проверяется, что нестандартные значения её не ломают.
 */
class WorkingHoursTest {

    /** 09:00 UTC = 14:00 в Ташкенте. */
    private val now: Instant = Instant.parse("2026-09-04T09:00:00Z")

    private val today = LocalDate.of(2026, 9, 4)

    @Test
    fun `future day gets the whole grid`() {
        val times = WorkingHours.times(date = today.plusDays(1), now = now)

        assertEquals(WorkingHours.DEFAULT_OPENS_AT, times.first())
        assertEquals(WorkingHours.DEFAULT_LAST_START, times.last())
        // 08:00…19:30 с шагом полчаса — 24 значения.
        assertEquals(24, times.size)
    }

    @Test
    fun `today keeps only what has not passed yet`() {
        val times = WorkingHours.times(date = today, now = now)

        assertEquals(LocalTime.of(14, 0), times.first())
        assertTrue(times.none { it.isBefore(LocalTime.of(14, 0)) })
    }

    @Test
    fun `past day offers nothing`() {
        assertTrue(WorkingHours.times(date = today.minusDays(1), now = now).isEmpty())
    }

    @Test
    fun `custom bounds are respected`() {
        val times = WorkingHours.times(
            date = today.plusDays(1),
            now = now,
            opensAt = LocalTime.of(9, 0),
            lastStart = LocalTime.of(10, 0),
            stepMinutes = 60,
        )

        assertEquals(listOf(LocalTime.of(9, 0), LocalTime.of(10, 0)), times)
    }

    /**
     * Последний час раньше первого — вырожденная настройка, но она не должна
     * ни зациклить генератор, ни выдать час, которого нет в интервале.
     */
    @Test
    fun `empty interval offers nothing`() {
        val times = WorkingHours.times(
            date = today.plusDays(1),
            now = now,
            opensAt = LocalTime.of(20, 0),
            lastStart = LocalTime.of(8, 0),
        )

        assertTrue(times.isEmpty())
    }
}
