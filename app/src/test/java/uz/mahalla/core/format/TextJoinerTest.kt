package uz.mahalla.core.format

import org.junit.Assert.assertEquals
import org.junit.Test

class TextJoinerTest {

    @Test
    fun `joins two non-blank parts through the template`() {
        assertEquals("Toshkent · 10:00", TextJoiner.join(TEMPLATE, "Toshkent", "10:00"))
    }

    @Test
    fun `drops null and blank parts instead of leaving a bare separator`() {
        assertEquals("Toshkent", TextJoiner.join(TEMPLATE, "Toshkent", null))
        assertEquals("Toshkent", TextJoiner.join(TEMPLATE, null, "Toshkent"))
        assertEquals("Toshkent", TextJoiner.join(TEMPLATE, "Toshkent", " "))
    }

    @Test
    fun `no parts at all give an empty string`() {
        assertEquals("", TextJoiner.join(TEMPLATE, null, null))
        assertEquals("", TextJoiner.join(TEMPLATE, emptyList()))
    }

    @Test
    fun `more than two parts chain through the same template`() {
        assertEquals("a · b · c", TextJoiner.join(TEMPLATE, listOf("a", "b", "c")))
    }

    private companion object {
        const val TEMPLATE = "%1\$s · %2\$s"
    }
}
