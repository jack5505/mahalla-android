package uz.mahalla.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Подписи расстояния и рейтинга в выдаче (эпик 4). */
class PlaceFormattersTest {

    @Test
    fun `metres are rounded to tens`() {
        // Точность до метра ничего не даёт и создаёт ложное ощущение измерения.
        assertEquals("450", DistanceFormatter.value(456))
        assertEquals("120", DistanceFormatter.value(120))
        assertEquals("990", DistanceFormatter.value(999))
    }

    @Test
    fun `very short distances keep their value`() {
        assertEquals("0", DistanceFormatter.value(0))
        assertEquals("7", DistanceFormatter.value(7))
    }

    @Test
    fun `a negative distance never leaks to the screen`() {
        assertEquals("0", DistanceFormatter.value(-100))
    }

    @Test
    fun `kilometres start exactly at a thousand metres`() {
        assertFalse(DistanceFormatter.isKilometers(999))
        assertTrue(DistanceFormatter.isKilometers(1_000))
        assertEquals("1,0", DistanceFormatter.value(1_000))
        assertEquals("1,2", DistanceFormatter.value(1_240))
    }

    @Test
    fun `from ten kilometres the fraction is dropped`() {
        assertEquals("12", DistanceFormatter.value(12_400))
        assertEquals("10", DistanceFormatter.value(10_000))
    }

    @Test
    fun `rating always has one decimal`() {
        // Иначе строки в списке разной длины и колонка «прыгает».
        assertEquals("4,0", RatingFormatter.format(4.0))
        assertEquals("4,8", RatingFormatter.format(4.75))
        assertEquals("4,7", RatingFormatter.format(4.74))
    }

    @Test
    fun `a place without reviews has no rating label`() {
        assertNull(RatingFormatter.format(0.0))
        assertNull(RatingFormatter.format(4.5, reviewCount = 0))
    }

    @Test
    fun `review count uses the same grouping as money`() {
        assertEquals("12", RatingFormatter.reviewCount(12))
        assertEquals(
            "1${MoneyFormatter.GROUPING_SEPARATOR}200",
            RatingFormatter.reviewCount(1_200),
        )
    }

    @Test
    fun `decimal separator does not depend on the system locale`() {
        // Вывод должен совпадать с макетом на обоих языках приложения.
        assertTrue(DistanceFormatter.value(1_500).contains(','))
        assertFalse(RatingFormatter.format(4.5)!!.contains('.'))
    }
}
