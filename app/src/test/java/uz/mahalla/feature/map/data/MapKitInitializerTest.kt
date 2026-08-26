package uz.mahalla.feature.map.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Порядок вызовов MapKit (эпик 4.2). SDK не прощает ни `initialize` без ключа,
 * ни второй `initialize`, а увидеть это можно только на устройстве — поэтому
 * порядок и однократность зафиксированы тестом.
 */
class MapKitInitializerTest {

    private class FakeSdk(var failOnInitialize: Boolean = false) : MapKitSdk {
        val calls = mutableListOf<String>()
        var initializeCount = 0

        override fun setApiKey(apiKey: String) {
            calls += "apiKey=$apiKey"
        }

        override fun setLocale(locale: String) {
            calls += "locale=$locale"
        }

        override fun initialize() {
            initializeCount++
            calls += "initialize"
            if (failOnInitialize) error("MapKit не поднялся")
        }
    }

    private fun initializer(
        sdk: FakeSdk,
        apiKey: String = "key-123",
        locale: String = "ru_RU",
    ) = MapKitInitializer(apiKey = apiKey, locale = locale, sdk = sdk)

    @Test
    fun `sets key and locale before initialize`() {
        val sdk = FakeSdk()

        val state = initializer(sdk).ensureInitialized()

        assertEquals(MapEngineState.Ready, state)
        assertEquals(listOf("apiKey=key-123", "locale=ru_RU", "initialize"), sdk.calls)
    }

    @Test
    fun `initializes only once`() {
        val sdk = FakeSdk()
        val initializer = initializer(sdk)

        repeat(3) { assertEquals(MapEngineState.Ready, initializer.ensureInitialized()) }

        assertEquals(1, sdk.initializeCount)
    }

    @Test
    fun `blank key never touches the sdk`() {
        val sdk = FakeSdk()
        val initializer = initializer(sdk, apiKey = "   ")

        assertFalse(initializer.hasApiKey)
        assertEquals(MapEngineState.MissingApiKey, initializer.ensureInitialized())
        assertTrue(sdk.calls.isEmpty())
    }

    @Test
    fun `failed initialize is not fatal and can be retried`() {
        val sdk = FakeSdk(failOnInitialize = true)
        val initializer = initializer(sdk)

        assertEquals(MapEngineState.Failed, initializer.ensureInitialized())

        // Провал не кэшируется: кнопка «Повторить» на экране обязана работать.
        sdk.failOnInitialize = false
        assertEquals(MapEngineState.Ready, initializer.ensureInitialized())
        assertEquals(2, sdk.initializeCount)
    }

    @Test
    fun `maps app language to mapkit locale`() {
        assertEquals("ru_RU", mapKitLocale("ru"))
        assertEquals("uz_UZ", mapKitLocale("uz"))
        assertEquals("uz_UZ", mapKitLocale("uz-Latn-UZ"))
        assertEquals("ru_RU", mapKitLocale("ru_RU"))
        // Неизвестный язык уходит в английский, а не в пустую строку: на пустой
        // локали MapKit ругается.
        assertEquals("en_US", mapKitLocale("kk"))
        assertEquals("en_US", mapKitLocale(""))
    }
}
