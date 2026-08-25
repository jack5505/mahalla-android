package uz.mahalla.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TicketFormatterTest {

    @Test
    fun `pads the number to three digits`() {
        assertEquals("A-001", TicketFormatter.format('A', 1))
        assertEquals("A-042", TicketFormatter.format('A', 42))
        assertEquals("B-999", TicketFormatter.format('B', 999))
    }

    @Test
    fun `longer numbers are not truncated`() {
        assertEquals("C-1234", TicketFormatter.format('C', 1234))
    }

    @Test
    fun `parse is the inverse of format`() {
        val ticket = TicketFormatter.format('A', 42)
        assertEquals('A' to 42, TicketFormatter.parse(ticket))
    }

    @Test
    fun `parse rejects malformed tickets`() {
        assertNull(TicketFormatter.parse(""))
        assertNull(TicketFormatter.parse("A042"))
        assertNull(TicketFormatter.parse("AB-042"))
        assertNull(TicketFormatter.parse("A-xyz"))
    }
}
