package uz.mahalla.feature.map.domain

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** Точка, выбранная на карте (issue #90): разбор и запись аргумента маршрута. */
class MapPointTest {

    private val defaultLocale: Locale = Locale.getDefault()

    @After
    fun tearDown() {
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun `point survives the trip through a route argument`() {
        val point = MapPoint(latitude = 41.311081, longitude = 69.240562)

        assertEquals(point, MapPoint.decode(point.encode()))
    }

    @Test
    fun `russian locale does not turn the separator into a decimal comma`() {
        // На русской локали `%f` даёт `41,311081` — разделитель полей совпал бы
        // с разделителем дробной части, и точка приехала бы разобранной надвое
        // (та же грабля, что у GeoHeaderInterceptor, issue #53).
        Locale.setDefault(Locale("ru", "RU"))
        val point = MapPoint(latitude = 41.311081, longitude = 69.240562)

        assertEquals("41.311081,69.240562", point.encode())
        assertEquals(point, MapPoint.decode(point.encode()))
    }

    @Test
    fun `coordinates are shown to the person with both values`() {
        assertEquals(
            "41.311081, 69.240562",
            MapPoint(latitude = 41.311081, longitude = 69.240562).formatted(),
        )
    }

    @Test
    fun `garbage decodes to nothing instead of throwing`() {
        listOf(null, "", "41.31", "41.31,", ",69.24", "abc,def", "41.31,69.24,16", "41,31;69,24")
            .forEach { raw -> assertNull(raw, MapPoint.decode(raw)) }
    }

    @Test
    fun `spaces around the values are tolerated`() {
        assertEquals(
            MapPoint(latitude = 41.311081, longitude = 69.240562),
            MapPoint.decode("  41.311081 , 69.240562  "),
        )
    }

    @Test
    fun `coordinates outside the planet are not a point`() {
        assertNull(MapPoint.of(latitude = 91.0, longitude = 69.24))
        assertNull(MapPoint.of(latitude = -90.5, longitude = 69.24))
        assertNull(MapPoint.of(latitude = 41.31, longitude = 180.5))
        assertNull(MapPoint.of(latitude = 41.31, longitude = -181.0))
        assertNull(MapPoint.of(latitude = Double.NaN, longitude = 69.24))
        assertNull(MapPoint.of(latitude = 41.31, longitude = Double.POSITIVE_INFINITY))
    }

    @Test
    fun `edges of the planet are valid`() {
        assertEquals(MapPoint(90.0, 180.0), MapPoint.of(latitude = 90.0, longitude = 180.0))
        assertEquals(MapPoint(-90.0, -180.0), MapPoint.of(latitude = -90.0, longitude = -180.0))
    }

    @Test
    fun `decoded value keeps six digits, not more`() {
        // Больше шести знаков карта всё равно не различает, а лишние цифры в
        // аргументе маршрута только удлиняют его.
        val encoded = MapPoint(latitude = 41.3110814523, longitude = 69.2405627891).encode()

        assertEquals("41.311081,69.240563", encoded)
    }
}
