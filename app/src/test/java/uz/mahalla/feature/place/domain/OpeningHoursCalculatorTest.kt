package uz.mahalla.feature.place.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/** «Открыто сейчас» по расписанию (эпик 4.4). */
class OpeningHoursCalculatorTest {

    @Test
    fun `inside the interval the place is open`() {
        val hours = listOf(day(DayOfWeek.MONDAY, "09:00", "18:00"))

        assertTrue(OpeningHoursCalculator.isOpenAt(hours, monday("12:00"))!!)
    }

    @Test
    fun `opening moment counts as open and closing moment does not`() {
        val hours = listOf(day(DayOfWeek.MONDAY, "09:00", "18:00"))

        assertTrue(OpeningHoursCalculator.isOpenAt(hours, monday("09:00"))!!)
        // В 18:00 заведение уже закрыто — иначе «до 18:00» означало бы 18:01.
        assertFalse(OpeningHoursCalculator.isOpenAt(hours, monday("18:00"))!!)
    }

    @Test
    fun `before opening the place is closed`() {
        val hours = listOf(day(DayOfWeek.MONDAY, "09:00", "18:00"))

        assertFalse(OpeningHoursCalculator.isOpenAt(hours, monday("08:59"))!!)
    }

    @Test
    fun `an overnight shift stays open after midnight`() {
        // 18:00–02:00: наивное сравнение `now in opens..closes` в 01:00 даёт
        // «закрыто», хотя заведение работает.
        val hours = listOf(
            day(DayOfWeek.MONDAY, "18:00", "02:00"),
            day(DayOfWeek.TUESDAY, "18:00", "02:00"),
        )

        assertTrue(OpeningHoursCalculator.isOpenAt(hours, monday("23:30"))!!)
        assertTrue(OpeningHoursCalculator.isOpenAt(hours, tuesday("01:00"))!!)
        assertFalse(OpeningHoursCalculator.isOpenAt(hours, tuesday("03:00"))!!)
    }

    @Test
    fun `yesterday's shift does not leak into a day off`() {
        val hours = listOf(
            day(DayOfWeek.MONDAY, "18:00", "02:00"),
            OpeningHours(DayOfWeek.TUESDAY, opensAt = null, closesAt = null),
        )

        assertTrue("ночная смена ещё идёт", OpeningHoursCalculator.isOpenAt(hours, tuesday("01:00"))!!)
        assertFalse("а днём вторник выходной", OpeningHoursCalculator.isOpenAt(hours, tuesday("12:00"))!!)
    }

    @Test
    fun `around the clock is always open`() {
        val hours = listOf(day(DayOfWeek.MONDAY, "00:00", "00:00"))

        assertTrue(OpeningHoursCalculator.isOpenAt(hours, monday("03:00"))!!)
        assertTrue(OpeningHoursCalculator.isOpenAt(hours, monday("23:59"))!!)
    }

    @Test
    fun `day off is closed`() {
        val hours = listOf(OpeningHours(DayOfWeek.MONDAY, opensAt = null, closesAt = null))

        assertFalse(OpeningHoursCalculator.isOpenAt(hours, monday("12:00"))!!)
    }

    @Test
    fun `no schedule means unknown, not closed`() {
        // «Закрыто» и «мы не знаем» — разные сообщения на карточке.
        assertNull(OpeningHoursCalculator.isOpenAt(emptyList(), monday("12:00")))
    }

    @Test
    fun `a day missing from the answer is unknown when it is the only one`() {
        val hours = listOf(day(DayOfWeek.FRIDAY, "09:00", "18:00"))

        assertNull(OpeningHoursCalculator.isOpenAt(hours, monday("12:00")))
    }

    @Test
    fun `week schedule always has seven days starting on monday`() {
        val week = OpeningHoursCalculator.weekSchedule(
            listOf(day(DayOfWeek.WEDNESDAY, "09:00", "18:00")),
        )

        assertEquals(7, week.size)
        assertEquals(DayOfWeek.MONDAY, week.first().dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, week.last().dayOfWeek)
        assertTrue(week.first().isDayOff)
        assertFalse(week[2].isDayOff)
    }

    @Test
    fun `interval flags classify the three shapes`() {
        assertTrue(day(DayOfWeek.MONDAY, "18:00", "02:00").isOvernight)
        assertTrue(day(DayOfWeek.MONDAY, "00:00", "00:00").isAroundTheClock)
        assertFalse(day(DayOfWeek.MONDAY, "09:00", "18:00").isOvernight)
    }

    private fun day(dayOfWeek: DayOfWeek, opens: String, closes: String) =
        OpeningHours(dayOfWeek, LocalTime.parse(opens), LocalTime.parse(closes))

    private fun monday(time: String): LocalDateTime =
        LocalDateTime.of(2026, 8, 24, 0, 0).with(LocalTime.parse(time))

    private fun tuesday(time: String): LocalDateTime =
        LocalDateTime.of(2026, 8, 25, 0, 0).with(LocalTime.parse(time))
}
