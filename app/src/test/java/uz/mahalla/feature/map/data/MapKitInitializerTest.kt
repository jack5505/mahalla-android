package uz.mahalla.feature.map.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/**
 * Порядок вызовов MapKit (эпик 4.2). SDK не прощает ни `initialize` без ключа,
 * ни второй `initialize`, ни вызов из фонового потока, а увидеть это можно
 * только на устройстве — поэтому порядок и однократность зафиксированы тестом.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapKitInitializerTest {

    private class FakeSdk(var failure: Throwable? = null) : MapKitSdk {
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
            failure?.let { throw it }
        }
    }

    private fun initializer(
        sdk: FakeSdk,
        apiKey: suspend () -> String = { "key-123" },
        locale: String = "ru_RU",
    ) = MapKitInitializer(
        apiKey = apiKey,
        locale = locale,
        sdk = sdk,
        // Боевой диспетчер — Dispatchers.Main.immediate: MapKit живёт на
        // главном потоке. В тесте главного потока нет, подставляем свой.
        mainDispatcher = UnconfinedTestDispatcher(),
    )

    @Test
    fun `sets key and locale before initialize`() = runTest {
        val sdk = FakeSdk()

        val state = initializer(sdk).ensureInitialized()

        assertEquals(MapEngineState.Ready, state)
        assertEquals(listOf("apiKey=key-123", "locale=ru_RU", "initialize"), sdk.calls)
    }

    @Test
    fun `initializes only once`() = runTest {
        val sdk = FakeSdk()
        val initializer = initializer(sdk)

        repeat(3) { assertEquals(MapEngineState.Ready, initializer.ensureInitialized()) }

        assertEquals(1, sdk.initializeCount)
    }

    @Test
    fun `blank key never touches the sdk`() = runTest {
        val sdk = FakeSdk()
        val initializer = initializer(sdk, apiKey = { "   " })

        assertEquals(MapEngineState.MissingApiKey, initializer.ensureInitialized())
        assertTrue(sdk.calls.isEmpty())
    }

    /**
     * Ключ вводят прямо в приложении (issue #129), и «Повторить» после ввода
     * обязано поднять движок без перезапуска: отсутствие ключа не кэшируется
     * так же, как не кэшируется провал инициализации.
     */
    @Test
    fun `key entered after a failed attempt is picked up`() = runTest {
        val sdk = FakeSdk()
        var key = ""
        val initializer = initializer(sdk, apiKey = { key })

        assertEquals(MapEngineState.MissingApiKey, initializer.ensureInitialized())

        key = "key-from-user"
        assertEquals(MapEngineState.Ready, initializer.ensureInitialized())
        assertEquals(listOf("apiKey=key-from-user", "locale=ru_RU", "initialize"), sdk.calls)
    }

    /** Ключ спрашивается один раз: после успеха SDK второй раз не поднимают. */
    @Test
    fun `key is asked only until the engine is up`() = runTest {
        val sdk = FakeSdk()
        var asked = 0
        val initializer = initializer(sdk, apiKey = { asked++; "key-123" })

        repeat(3) { initializer.ensureInitialized() }

        assertEquals(1, asked)
    }

    @Test
    fun `failed initialize is not fatal and can be retried`() = runTest {
        val sdk = FakeSdk(failure = IllegalStateException("MapKit не поднялся"))
        val initializer = initializer(sdk)

        assertEquals(MapEngineState.Failed, initializer.ensureInitialized())

        // Провал не кэшируется: кнопка «Повторить» на экране обязана работать.
        sdk.failure = null
        assertEquals(MapEngineState.Ready, initializer.ensureInitialized())
        assertEquals(2, sdk.initializeCount)
    }

    /**
     * MapKit сообщает о нарушении своего контракта `AssertionError`, а на
     * неподходящей ABI кидает `UnsatisfiedLinkError` — оба не `Exception`, то
     * есть мимо обычного `catch`. Экран карты обязан показать ошибку, а не
     * уронить приложение.
     */
    @Test
    fun `errors from the sdk become Failed instead of crashing`() = runTest {
        val assertionSdk = FakeSdk(failure = AssertionError("wrong thread"))
        assertEquals(MapEngineState.Failed, initializer(assertionSdk).ensureInitialized())

        val linkageSdk = FakeSdk(failure = UnsatisfiedLinkError("libmaps-mobile.so"))
        assertEquals(MapEngineState.Failed, initializer(linkageSdk).ensureInitialized())
    }

    /**
     * MapKit проверяет поток: `initialize` из фонового кидает `AssertionError`
     * внутри SDK. Раньше инициализация уходила на `Dispatchers.IO` — тест
     * закрепляет, что все три вызова идут через переданный диспетчер.
     */
    @Test
    fun `sdk is touched only on the main dispatcher`() = runTest {
        var insideDispatcher = false
        val mainDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                insideDispatcher = true
                try {
                    block.run()
                } finally {
                    insideDispatcher = false
                }
            }
        }
        val seen = mutableListOf<Boolean>()
        val sdk = object : MapKitSdk {
            override fun setApiKey(apiKey: String) { seen += insideDispatcher }
            override fun setLocale(locale: String) { seen += insideDispatcher }
            override fun initialize() { seen += insideDispatcher }
        }

        val state = MapKitInitializer(
            apiKey = { "key-123" },
            locale = "ru_RU",
            sdk = sdk,
            mainDispatcher = mainDispatcher,
        ).ensureInitialized()

        assertEquals(MapEngineState.Ready, state)
        assertEquals(listOf(true, true, true), seen)
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
