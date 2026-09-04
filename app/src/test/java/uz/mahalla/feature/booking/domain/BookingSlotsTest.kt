package uz.mahalla.feature.booking.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Слоты (issue #97). Считает их сервер — здесь проверяется единственное
 * клиентское правило: прошедшее время не предлагать, — и то, что оно работает
 * в зоне заведения, а не в зоне устройства.
 */
class BookingSlotsTest {

    @Test
    fun `slots come back parsed, sorted and without duplicates`() {
        // `"10:00"` и `"10:00:00"` — одно и то же время: сервер отдаёт
        // `LocalTime` то так, то так, а в списке это был бы дубликат ключа.
        val slots = BookingSlots.available(
            raw = listOf("11:30:00", "10:00", "10:00:00", "09:15"),
            date = TOMORROW,
            now = NOW,
        )

        assertEquals(
            listOf(LocalTime.of(9, 15), LocalTime.of(10, 0), LocalTime.of(11, 30)),
            slots,
        )
    }

    @Test
    fun `garbage drops out without taking the rest of the day with it`() {
        val slots = BookingSlots.available(
            raw = listOf("не время", "", "25:00", "10:xx", "12:00"),
            date = TOMORROW,
            now = NOW,
        )

        assertEquals(listOf(LocalTime.of(12, 0)), slots)
    }

    /**
     * Главное правило issue: слот, который уже наступил, не предлагается.
     * Сервер вполне может отдать его — он считает занятость, а не часы.
     */
    @Test
    fun `today the slots that have already started are dropped`() {
        // NOW — 09:00 UTC, то есть 14:00 в Ташкенте.
        val slots = BookingSlots.available(
            raw = listOf("09:00", "13:59", "14:00", "14:30"),
            date = TODAY_IN_TASHKENT,
            now = NOW,
        )

        // 14:00 ровно — ещё можно: минута в минуту слот не считается упущенным.
        assertEquals(listOf(LocalTime.of(14, 0), LocalTime.of(14, 30)), slots)
    }

    @Test
    fun `a future day keeps the morning slots`() {
        val slots = BookingSlots.available(
            raw = listOf("09:00", "14:30"),
            date = TOMORROW,
            now = NOW,
        )

        assertEquals(listOf(LocalTime.of(9, 0), LocalTime.of(14, 30)), slots)
    }

    @Test
    fun `a past day gives nothing, whatever the server offered`() {
        val slots = BookingSlots.available(
            raw = listOf("09:00", "23:00"),
            date = TODAY_IN_TASHKENT.minusDays(1),
            now = NOW,
        )

        assertTrue(slots.isEmpty())
    }

    /**
     * Зона заведения, а не устройства: в 21:00 UTC в Ташкенте уже завтра, и
     * «сегодня» для календаря — тоже завтрашний день.
     */
    @Test
    fun `the day is counted in Tashkent, not in UTC`() {
        val lateEvening = Instant.parse("2026-09-04T21:00:00Z")

        assertEquals(LocalDate.of(2026, 9, 5), BookingSlots.today(lateEvening))
        assertEquals(LocalDate.of(2026, 9, 5), BookingSlots.dates(lateEvening).first())
    }

    @Test
    fun `the calendar starts today and runs two weeks`() {
        val dates = BookingSlots.dates(NOW)

        assertEquals(BookingSlots.CALENDAR_DAYS, dates.size)
        assertEquals(TODAY_IN_TASHKENT, dates.first())
        assertEquals(TODAY_IN_TASHKENT.plusDays(13), dates.last())
        // Дни не повторяются и идут по порядку — иначе в календаре было бы два
        // одинаковых чипа.
        assertEquals(dates.sorted(), dates)
        assertEquals(dates.size, dates.toSet().size)
    }

    @Test
    fun `the start of a slot is a moment in the zone of the place`() {
        val starts = BookingSlots.startsAt(LocalDate.of(2026, 9, 5), LocalTime.of(10, 0))

        // 10:00 в Ташкенте — это 05:00 UTC.
        assertEquals(Instant.parse("2026-09-05T05:00:00Z"), starts)
    }

    private companion object {
        /** 09:00 UTC = 14:00 в Ташкенте. */
        val NOW: Instant = Instant.parse("2026-09-04T09:00:00Z")
        val TODAY_IN_TASHKENT: LocalDate = LocalDate.of(2026, 9, 4)
        val TOMORROW: LocalDate = LocalDate.of(2026, 9, 5)
    }
}
