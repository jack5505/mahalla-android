package uz.mahalla.testutil

import kotlinx.coroutines.CompletableDeferred
import uz.mahalla.feature.map.canvas.MapCoordinates
import uz.mahalla.feature.map.data.MapKitInitializer
import uz.mahalla.feature.map.data.MapKitSdk
import uz.mahalla.feature.map.data.UserLocationProvider

/**
 * Координаты без MapKit: на JVM SDK не поднимается, а карта обязана быть
 * проверяемой без устройства.
 *
 * [gate] позволяет задержать ответ — этим проверяется, что второй тап по «моему
 * местоположению» не запускает второй запрос.
 */
class FakeUserLocationProvider(
    var location: MapCoordinates? = null,
) : UserLocationProvider {

    var callCount: Int = 0
        private set

    var gate: CompletableDeferred<Unit>? = null

    override suspend fun currentLocation(): MapCoordinates? {
        callCount++
        gate?.await()
        return location
    }
}

/**
 * Ворота инициализации MapKit с SDK-заглушкой: ViewModel их только передаёт
 * экрану, но конструктор без них не собрать.
 */
fun fakeMapKitInitializer(): MapKitInitializer = MapKitInitializer(
    apiKey = "test-key",
    locale = "uz_UZ",
    sdk = object : MapKitSdk {
        override fun setApiKey(apiKey: String) = Unit
        override fun setLocale(locale: String) = Unit
        override fun initialize() = Unit
    },
)
