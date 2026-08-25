package uz.mahalla.core.format

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DateTimeFormattersTest {

    // 2026-08-25T09:05:00Z = 14:05 в Ташкенте (UTC+5).
    private val instant = Instant.parse("2026-08-25T09:05:00Z")

    @Test
    fun `formats date and time in the Tashkent zone`() {
        assertEquals("25.08.2026", DateTimeFormatters.date(instant))
        assertEquals("14:05", DateTimeFormatters.time(instant))
        assertEquals("25.08.2026, 14:05", DateTimeFormatters.dateTime(instant))
    }

    @Test
    fun `app zone is Asia Tashkent`() {
        assertEquals(ZoneId.of("Asia/Tashkent"), DateTimeFormatters.AppZone)
    }

    @Test
    fun `explicit zone overrides the default one`() {
        assertEquals("09:05", DateTimeFormatters.time(instant, ZoneId.of("UTC")))
    }

    @Test
    fun `waiting time switches to hours after an hour`() {
        assertEquals("0", DateTimeFormatters.waitingTime(0))
        assertEquals("12", DateTimeFormatters.waitingTime(12))
        assertEquals("59", DateTimeFormatters.waitingTime(59))
        assertEquals("1:00", DateTimeFormatters.waitingTime(60))
        assertEquals("1:05", DateTimeFormatters.waitingTime(65))
        assertEquals("2:30", DateTimeFormatters.waitingTime(150))
    }
}
