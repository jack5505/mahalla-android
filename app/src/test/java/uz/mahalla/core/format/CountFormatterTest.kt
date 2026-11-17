package uz.mahalla.core.format

import org.junit.Assert.assertEquals
import org.junit.Test

/** Счётчики (лайки, отзывы) — та же группировка разрядов, что у сумм. */
class CountFormatterTest {

    @Test
    fun `small counts are shown as is`() {
        assertEquals("0", CountFormatter.count(0))
        assertEquals("999", CountFormatter.count(999))
    }

    @Test
    fun `counts from a thousand are grouped like money`() {
        assertEquals("1${MoneyFormatter.GROUPING_SEPARATOR}000", CountFormatter.count(1_000))
        assertEquals("12${MoneyFormatter.GROUPING_SEPARATOR}345", CountFormatter.count(12_345))
    }

    @Test
    fun `a negative count never leaks to the screen`() {
        assertEquals("0", CountFormatter.count(-5))
        assertEquals("0", CountFormatter.count(-5L))
    }

    @Test
    fun `the long overload matches the int overload`() {
        assertEquals(CountFormatter.count(12_345), CountFormatter.count(12_345L))
    }
}
