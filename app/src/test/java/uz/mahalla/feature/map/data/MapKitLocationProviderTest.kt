package uz.mahalla.feature.map.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Моё местоположение» без поднятого SDK (эпик 4.2).
 *
 * Сам запрос координат проверяется только на устройстве — MapKit на JVM не
 * поднимается. Зато проверяется важное: без ключа провайдер не трогает SDK
 * вообще, иначе сборка без `MAPKIT_API_KEY` падала бы при первом же нажатии
 * на кнопку «моё местоположение».
 */
class MapKitLocationProviderTest {

    private class NeverCalledSdk : MapKitSdk {
        var touched = false
        override fun setApiKey(apiKey: String) { touched = true }
        override fun setLocale(locale: String) { touched = true }
        override fun initialize() { touched = true }
    }

    @Test
    fun `without api key there is no location and no sdk call`() = runTest {
        val sdk = NeverCalledSdk()
        val provider = MapKitLocationProvider(
            initializer = MapKitInitializer(apiKey = "", locale = "ru_RU", sdk = sdk),
        )

        assertNull(provider.currentLocation())
        assertTrue("SDK не должен подниматься без ключа", !sdk.touched)
    }

    @Test
    fun `broken sdk gives no location instead of crashing the screen`() = runTest {
        val failing = object : MapKitSdk {
            override fun setApiKey(apiKey: String) = Unit
            override fun setLocale(locale: String) = Unit
            override fun initialize(): Unit = error("MapKit не поднялся")
        }
        val provider = MapKitLocationProvider(
            initializer = MapKitInitializer(apiKey = "key", locale = "ru_RU", sdk = failing),
        )

        assertNull(provider.currentLocation())
    }
}
