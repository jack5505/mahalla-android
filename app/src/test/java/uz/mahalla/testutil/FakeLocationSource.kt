package uz.mahalla.testutil

import uz.mahalla.data.location.DeviceLocation
import uz.mahalla.data.location.LocationSource

/**
 * Координаты устройства без `LocationManager`. `null` — обычный случай на
 * экране телефона: разрешение на геолокацию онбординг просит позже.
 */
class FakeLocationSource(
    var location: DeviceLocation? = null,
) : LocationSource {

    /** Сколько раз спросили: точку с карты позиция устройства не перебивает. */
    var callCount: Int = 0
        private set

    override suspend fun lastKnown(): DeviceLocation? {
        callCount++
        return location
    }
}
