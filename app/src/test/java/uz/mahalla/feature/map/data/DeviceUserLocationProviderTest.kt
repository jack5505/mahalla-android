package uz.mahalla.feature.map.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uz.mahalla.data.location.DeviceLocation
import uz.mahalla.feature.map.canvas.MapCoordinates
import uz.mahalla.testutil.FakeLocationSource
import uz.mahalla.testutil.FakeUserLocationProvider

/**
 * Запасной источник координат (issue #126).
 *
 * До него «моё местоположение» умело спрашивать только MapKit, а тот выходит на
 * первой строке, пока движок не поднят: в сборке без `MAPKIT_API_KEY` человек с
 * выданным разрешением видел «Joylashuvni aniqlab bo'lmadi» всегда.
 */
class DeviceUserLocationProviderTest {

    private val mapKit = FakeUserLocationProvider()
    private val system = FakeLocationSource()

    @Test
    fun `mapkit coordinates win`() = runTest {
        // Позиция MapKit свежая, системная — последняя известная, то есть
        // сколь угодно старая. Спрашивать её первой значило бы навсегда
        // закрыть дорогу свежим координатам.
        mapKit.location = MapCoordinates(41.5, 69.5)
        system.location = DeviceLocation(39.65, 66.96)

        assertEquals(MapCoordinates(41.5, 69.5), provider().currentLocation())
        assertEquals(0, system.callCount)
    }

    @Test
    fun `without mapkit the system location answers`() = runTest {
        mapKit.location = null
        system.location = DeviceLocation(41.31, 69.28)

        assertEquals(MapCoordinates(41.31, 69.28), provider().currentLocation())
        assertEquals(1, system.callCount)
    }

    @Test
    fun `no coordinates anywhere stays no coordinates`() = runTest {
        // Отсутствие координат — норма (разрешения нет, геолокация выключена),
        // и придумывать их запасной источник не должен.
        mapKit.location = null
        system.location = null

        assertNull(provider().currentLocation())
    }

    private fun provider() = DeviceUserLocationProvider(mapKit = mapKit, system = system)
}
