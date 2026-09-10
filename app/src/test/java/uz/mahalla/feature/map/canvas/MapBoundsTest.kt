package uz.mahalla.feature.map.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Видимая область карты (issue #168): по ней грузятся маркеры, поэтому
 * геометрия проверяется отдельно от ViewModel.
 */
class MapBoundsTest {

    @Test
    fun `a normal frame is a valid area`() {
        assertTrue(bounds(41.30, 69.20, 41.35, 69.30).isValid)
    }

    @Test
    fun `an inverted rectangle is not an area`() {
        // Перепутанные местами углы дали бы пустой ответ сервера, и карта без
        // маркеров выглядела бы как «рядом ничего нет».
        assertFalse(bounds(41.35, 69.20, 41.30, 69.30).isValid)
        assertFalse(bounds(41.30, 69.30, 41.35, 69.20).isValid)
    }

    @Test
    fun `an area shrunk to a point is not an area`() {
        // Так выглядит visibleRegion, прочитанный до того, как окно карты
        // промерено: все четыре угла в одной точке. Запрос по нему ушёл бы в
        // Гвинейский залив, а пустой ответ читался бы как «рядом ничего нет».
        assertFalse(bounds(0.0, 0.0, 0.0, 0.0).isValid)
        assertFalse(bounds(41.31, 69.28, 41.31, 69.28).isValid)
        // Полоска нулевой ширины — тоже не кадр.
        assertFalse(bounds(41.30, 69.20, 41.35, 69.20).isValid)
    }

    @Test
    fun `infinity and NaN are not an area`() {
        assertFalse(bounds(Double.NaN, 69.20, 41.35, 69.30).isValid)
        assertFalse(bounds(41.30, 69.20, Double.POSITIVE_INFINITY, 69.30).isValid)
    }

    @Test
    fun `coordinates outside the Earth are not an area`() {
        assertFalse(bounds(-91.0, 69.20, 41.35, 69.30).isValid)
        assertFalse(bounds(41.30, 69.20, 91.0, 69.30).isValid)
        assertFalse(bounds(41.30, -181.0, 41.35, 69.30).isValid)
        assertFalse(bounds(41.30, 69.20, 41.35, 181.0).isValid)
    }

    @Test
    fun `a frame inside the loaded area does not need a request`() {
        val loaded = bounds(41.20, 69.10, 41.40, 69.40)

        assertTrue(loaded.contains(bounds(41.25, 69.15, 41.35, 69.35)))
        // Та же область целиком — тоже внутри: повторять запрос не за чем.
        assertTrue(loaded.contains(loaded))
    }

    @Test
    fun `a frame reaching outside the loaded area needs a request`() {
        val loaded = bounds(41.20, 69.10, 41.40, 69.40)

        // Сдвиг на восток: правый край кадра ушёл за загруженное.
        assertFalse(loaded.contains(bounds(41.25, 69.15, 41.35, 69.50)))
        // Отдаление: кадр стал шире загруженного во все стороны.
        assertFalse(loaded.contains(bounds(41.00, 68.90, 41.60, 69.60)))
    }

    @Test
    fun `the margin widens the area by a fraction of its own size on each side`() {
        val expanded = bounds(41.00, 69.00, 41.20, 69.40).expandedBy(0.25)

        // Широта: разброс 0.2° — по 0.05° с каждой стороны.
        assertEquals(40.95, expanded.southWest.latitude, TOLERANCE)
        assertEquals(41.25, expanded.northEast.latitude, TOLERANCE)
        // Долгота: разброс 0.4° — по 0.1°.
        assertEquals(68.90, expanded.southWest.longitude, TOLERANCE)
        assertEquals(69.50, expanded.northEast.longitude, TOLERANCE)
    }

    @Test
    fun `the margin does not take the area off the Earth`() {
        // На мелком зуме в кадр попадает вся планета, и запас упёрся бы в
        // широту 90° с лишним — координата, которой не существует.
        val expanded = bounds(-89.0, -179.0, 89.0, 179.0).expandedBy(0.25)

        assertEquals(-90.0, expanded.southWest.latitude, TOLERANCE)
        assertEquals(90.0, expanded.northEast.latitude, TOLERANCE)
        assertEquals(-180.0, expanded.southWest.longitude, TOLERANCE)
        assertEquals(180.0, expanded.northEast.longitude, TOLERANCE)
        assertTrue(expanded.isValid)
    }

    private fun bounds(
        minLatitude: Double,
        minLongitude: Double,
        maxLatitude: Double,
        maxLongitude: Double,
    ) = MapBounds(
        southWest = MapCoordinates(minLatitude, minLongitude),
        northEast = MapCoordinates(maxLatitude, maxLongitude),
    )

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
