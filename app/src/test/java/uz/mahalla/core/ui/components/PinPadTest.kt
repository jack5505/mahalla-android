package uz.mahalla.core.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Раскладка нампада PIN (макет 0e).
 *
 * Проверка не косметическая: потерянная или задвоенная цифра означает PIN,
 * который человек не сможет ни задать, ни ввести, — а на глаз в сетке 3×4 это
 * не видно.
 */
class PinPadTest {

    @Test
    fun `pad has all ten digits exactly once`() {
        val digits = PadRows.flatten().filterNotNull().filter { it != BACKSPACE }

        assertEquals(('0'..'9').toList(), digits.sorted())
    }

    @Test
    fun `pad has a single backspace and a single empty slot`() {
        val keys = PadRows.flatten()

        assertEquals(1, keys.count { it == BACKSPACE })
        assertEquals(1, keys.count { it == null })
    }

    @Test
    fun `pad is three columns wide`() {
        assertTrue(PadRows.isNotEmpty())
        assertTrue("в ряду не три клавиши", PadRows.all { it.size == COLUMNS })
    }

    /**
     * Ноль стоит внизу по центру, а стирание — под цифрой 9: так набирают
     * вслепую на любом телефоне, и путать эти две клавиши нельзя.
     */
    @Test
    fun `zero sits in the middle of the last row`() {
        val lastRow = PadRows.last()

        assertEquals(null, lastRow[0])
        assertEquals('0', lastRow[1])
        assertEquals(BACKSPACE, lastRow[2])
    }

    private companion object {
        const val COLUMNS = 3
    }
}
