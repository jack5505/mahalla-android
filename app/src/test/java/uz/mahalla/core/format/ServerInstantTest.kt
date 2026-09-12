package uz.mahalla.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Разбор времени из ответов бэкенда. Две функции на зоне-менее строку — это
 * решение issue #144, и цена ошибки в нём ровно пять часов, поэтому правило
 * закреплено тестом здесь, а не только в вертикалях.
 */
class ServerInstantTest {

    @Test
    fun `an event stamp without a zone is UTC`() {
        // `createdAt` ставит сам сервер своими часами, а живёт он в UTC — его
        // же `Instant`-поля приезжают с `Z`.
        assertEquals(
            Instant.parse("2026-08-29T16:09:06.688Z"),
            parseServerInstant("2026-08-29T16:09:06.688"),
        )
    }

    @Test
    fun `a slot without a zone is Tashkent`() {
        // А слот выбрал человек по часам на стене: 13:00 — это 08:00 UTC.
        assertEquals(
            Instant.parse("2026-09-05T08:00:00Z"),
            parseServerSlotInstant("2026-09-05T13:00:00"),
        )
    }

    @Test
    fun `a slot matches the appointment date and time of the same hour`() {
        // Условие из issue #144: бронь игровой зоны на 13:00 и запись к
        // мастеру на 13:00 того же дня — один и тот же момент. Запись бэкенд
        // отдаёт двумя полями, `apptDate` + `startTime`, и они местные по
        // построению; трактовка слота должна совпадать с ними.
        val appointment = LocalDate.parse("2026-09-05")
            .atTime(requireNotNull(parseServerLocalTime("13:00:00")))
            .atZone(DateTimeFormatters.AppZone)
            .toInstant()

        assertEquals(appointment, parseServerSlotInstant("2026-09-05T13:00:00"))
    }

    @Test
    fun `an explicit zone wins over both rules`() {
        // Начнёт бэкенд отдавать момент — гадать будет не о чем, и обе
        // функции дадут одно и то же.
        val withZone = "2026-09-05T13:00:00Z"

        assertEquals(Instant.parse(withZone), parseServerInstant(withZone))
        assertEquals(Instant.parse(withZone), parseServerSlotInstant(withZone))
    }

    @Test
    fun `an unparsable value is null and not an exception`() {
        // Битое поле в одной записи не должно ронять весь список.
        listOf(null, "", "   ", "2026-09-05", "вчера").forEach { raw ->
            assertNull(raw, parseServerInstant(raw))
            assertNull(raw, parseServerSlotInstant(raw))
        }
    }

    @Test
    fun `a day without time is parsed softly too`() {
        assertEquals(LocalDate.parse("2026-09-10"), parseServerLocalDate("2026-09-10"))
        assertNull(parseServerLocalDate("10.09.2026"))
        assertNull(parseServerLocalDate(null))
    }
}
