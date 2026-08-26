package uz.mahalla.core.ui.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpFieldStateTest {

    @Test
    fun `keeps only digits and respects length`() {
        val state = OtpFieldState().onInput("12ab34-56789")

        assertEquals("123456", state.code)
        assertTrue(state.isComplete)
    }

    @Test
    fun `is incomplete until all digits are entered`() {
        val state = OtpFieldState().onInput("1234")

        assertFalse(state.isComplete)
        assertEquals(4, state.filledCount)
    }

    @Test
    fun `focused cell is the next empty one`() {
        assertEquals(0, OtpFieldState().focusedIndex)
        assertEquals(4, OtpFieldState().onInput("1234").focusedIndex)
        assertNull("код набран — подсвечивать нечего", OtpFieldState().onInput("123456").focusedIndex)
    }

    @Test
    fun `cells expose digits and gaps`() {
        val cells = OtpFieldState().onInput("12").cells()

        assertEquals(6, cells.size)
        assertEquals(listOf('1', '2', null, null, null, null), cells)
    }

    @Test
    fun `new input clears the error`() {
        val state = OtpFieldState().onInput("123456").asError().onInput("1")

        assertFalse(state.isError)
        assertEquals("1", state.code)
    }

    @Test
    fun `clearing keeps length and drops the code`() {
        val state = OtpFieldState(length = 4).onInput("1234").cleared()

        assertEquals("", state.code)
        assertEquals(4, state.length)
        assertFalse(state.isError)
    }

    @Test
    fun `custom length is honoured`() {
        val state = OtpFieldState(length = 4).onInput("123456")

        assertEquals("1234", state.code)
        assertTrue(state.isComplete)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero length is rejected`() {
        OtpFieldState(length = 0)
    }
}
