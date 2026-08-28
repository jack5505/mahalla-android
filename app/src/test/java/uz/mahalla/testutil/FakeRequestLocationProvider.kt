package uz.mahalla.testutil

import uz.mahalla.data.location.DeviceLocation
import uz.mahalla.data.location.RequestLocationProvider

/** Координаты для запросов авторизации без DataStore и `LocationManager`. */
class FakeRequestLocationProvider(
    var location: DeviceLocation = DEFAULT,
) : RequestLocationProvider {

    override suspend fun current(): DeviceLocation = location

    companion object {
        /** Центр Ташкента — то же, что отдаёт запасной путь в проде. */
        val DEFAULT = DeviceLocation(latitude = 41.311081, longitude = 69.240562)
    }
}
