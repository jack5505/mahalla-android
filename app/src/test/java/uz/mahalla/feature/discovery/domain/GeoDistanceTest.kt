package uz.mahalla.feature.discovery.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Расстояние до места (issue #53): его считает сервер, но в ответе поиска
 * этого поля нет, и без пересчёта вся выдача показывала бы «0 м».
 */
class GeoDistanceTest {

    @Test
    fun `distance to the same point is zero`() {
        assertEquals(0, GeoDistance.meters(TASHKENT, TASHKENT))
    }

    @Test
    fun `a known city distance is right within a percent`() {
        // Ташкент — Самарканд, по прямой ≈ 270 км.
        val meters = GeoDistance.meters(TASHKENT, SAMARKAND)

        assertTrue("$meters м", meters in 265_000..275_000)
    }

    @Test
    fun `a city block is measured in hundreds of meters`() {
        val meters = GeoDistance.meters(TASHKENT, GeoPoint(41.3111, 69.2857))

        assertTrue("$meters м", meters in 400..600)
    }

    @Test
    fun `direction does not change the distance`() {
        assertEquals(
            GeoDistance.meters(TASHKENT, SAMARKAND),
            GeoDistance.meters(SAMARKAND, TASHKENT),
        )
    }

    @Test
    fun `opposite points of the globe do not overflow the formula`() {
        // sqrt чуть больше единицы на округлении увёл бы asin за область
        // определения и дал NaN, а из него — отрицательное расстояние.
        val meters = GeoDistance.meters(GeoPoint(0.0, 0.0), GeoPoint(0.0, 180.0))

        assertTrue("$meters м", meters > 20_000_000)
    }

    private companion object {
        val TASHKENT = GeoPoint(41.3111, 69.2797)
        val SAMARKAND = GeoPoint(39.6542, 66.9597)
    }
}
