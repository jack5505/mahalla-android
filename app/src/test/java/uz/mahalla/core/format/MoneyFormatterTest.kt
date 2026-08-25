package uz.mahalla.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MoneyFormatterTest {

    private val nbsp = MoneyFormatter.GROUPING_SEPARATOR

    @Test
    fun `groups thousands`() {
        assertEquals("0", MoneyFormatter.amount(0))
        assertEquals("999", MoneyFormatter.amount(999))
        assertEquals("1${nbsp}000", MoneyFormatter.amount(1_000))
        assertEquals("1${nbsp}234${nbsp}567", MoneyFormatter.amount(1_234_567))
    }

    @Test
    fun `separator is a non breaking space, not a plain one`() {
        assertEquals(NON_BREAKING_SPACE_CODE, nbsp.code)
        assertFalse(MoneyFormatter.amount(1_000).contains(Char(PLAIN_SPACE_CODE)))
    }

    @Test
    fun `negative amounts keep the minus sign`() {
        assertEquals("-5${nbsp}000", MoneyFormatter.amount(-5_000))
    }

    @Test
    fun `currency label is separated from the amount`() {
        assertEquals("1${nbsp}000${nbsp}so'm", MoneyFormatter.withCurrency(1_000, "so'm"))
    }

    @Test
    fun `signed amount marks income explicitly`() {
        assertEquals("+1${nbsp}000", MoneyFormatter.signedAmount(1_000))
        assertEquals("-1${nbsp}000", MoneyFormatter.signedAmount(-1_000))
        assertEquals("0", MoneyFormatter.signedAmount(0))
    }

    private companion object {
        const val NON_BREAKING_SPACE_CODE = 0x00A0
        const val PLAIN_SPACE_CODE = 0x0020
    }
}
