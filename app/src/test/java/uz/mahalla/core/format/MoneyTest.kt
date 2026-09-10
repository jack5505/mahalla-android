package uz.mahalla.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Единица денег бэкенда — тийины, делитель сто (issue #149). До этого
 * приложение считало целые поля сумами и рисовало «5 000 000 so'm» за стрижку.
 */
class MoneyTest {

    @Test
    fun `a barber price from the stand becomes fifty thousand sums`() {
        // Живая проба стенда: `price: 5000000` у стрижки.
        assertEquals(50_000L, Money.tiyinToSom(5_000_000L))
        assertEquals(50_000L, 5_000_000L.tiyinToSom())
    }

    @Test
    fun `half a sum rounds up, less than half rounds down`() {
        assertEquals(2L, Money.tiyinToSom(150L))
        assertEquals(1L, Money.tiyinToSom(149L))
        assertEquals(0L, Money.tiyinToSom(49L))
        assertEquals(1L, Money.tiyinToSom(50L))
    }

    @Test
    fun `debits keep their sign and round the same way`() {
        assertEquals(-84_500L, Money.tiyinToSom(-8_450_000L))
        assertEquals(-1L, Money.tiyinToSom(-149L))
        assertEquals(-2L, Money.tiyinToSom(-150L))
    }

    @Test
    fun `an absent field stays absent`() {
        val absent: Long? = null
        assertNull(Money.tiyinToSom(absent))
        assertNull(absent.tiyinToSom())
        val present: Long? = 4_900_000L
        assertEquals(49_000L, present.tiyinToSom())
    }

    @Test
    fun `sums typed by a person go to the backend in tiyin`() {
        assertEquals(25_000_000L, Money.somToTiyin(250_000L))
        assertEquals(0L, Money.somToTiyin(0L))
    }

    @Test(expected = ArithmeticException::class)
    fun `an amount that does not fit in tiyin is an error, not a wrap-around`() {
        Money.somToTiyin(Long.MAX_VALUE / 10)
    }
}
