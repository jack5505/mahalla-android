package uz.mahalla.data.network

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.data.prefs.SettingsDataStore
import java.io.File

/**
 * Кэш адреса бэкенда (issue #26).
 *
 * `BackendUrlInterceptor` читает адрес синхронно, на потоке OkHttp, поэтому
 * важно и то, что кэш поднимается на старте, и то, что он обновляется сразу
 * при записи.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BackendUrlStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `without a saved address the build one is used`() = runTest {
        val store = store()

        store.hydrate()

        assertEquals(BUILD_URL, store.current)
        assertEquals(BUILD_URL, store.buildDefault)
        assertNull(store.saved.first())
    }

    @Test
    fun `hydrate lifts the saved address into the cache`() = runTest {
        val settings = settingsDataStore()
        settings.setBackendBaseUrl(SAVED_URL)

        val store = store(settings)
        // До поднятия кэша интерцептор ведёт запросы на адрес сборки.
        assertEquals(BUILD_URL, store.current)

        store.hydrate()

        assertEquals(SAVED_URL, store.current)
    }

    @Test
    fun `save applies the address immediately and persists it`() = runTest {
        val settings = settingsDataStore()
        val store = store(settings)

        store.save(SAVED_URL)

        assertEquals("адрес действует сразу", SAVED_URL, store.current)
        assertEquals(SAVED_URL, settings.current().backendBaseUrl)
        assertEquals(SAVED_URL, store.saved.first())
    }

    @Test
    fun `a saved address survives a restart`() = runTest {
        val settings = settingsDataStore()
        store(settings).save(SAVED_URL)

        // Новый процесс: тот же файл, новый экземпляр кэша.
        val restarted = store(settings)
        restarted.hydrate()

        assertEquals(SAVED_URL, restarted.current)
    }

    @Test
    fun `a build without the override ignores a saved address`() = runTest {
        // Релиз ходит только туда, куда его собрали: адрес мог приехать из
        // бэкапа настроек debug-установки.
        val settings = settingsDataStore()
        settings.setBackendBaseUrl(SAVED_URL)

        val store = store(settings, overrideEnabled = false)
        store.hydrate()

        assertEquals(BUILD_URL, store.current)
        assertNull("экрану показывать нечего", store.saved.first())
    }

    @Test
    fun `a build without the override does not save the address`() = runTest {
        val settings = settingsDataStore()
        val store = store(settings, overrideEnabled = false)

        store.save(SAVED_URL)

        assertEquals(BUILD_URL, store.current)
        assertNull(settings.current().backendBaseUrl)
    }

    private fun store(
        settings: SettingsDataStore = settingsDataStore(),
        overrideEnabled: Boolean = true,
    ) = BackendUrlStore(settings, BUILD_URL, overrideEnabled)

    private fun settingsDataStore() = SettingsDataStore(newDataStore())

    /** На один файл в процессе допустим ровно один экземпляр DataStore. */
    private fun newDataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { File(temporaryFolder.root, "backend-url.preferences_pb") },
    )

    private companion object {
        const val BUILD_URL = "http://10.0.2.2:8080/api/v1/"
        const val SAVED_URL = "http://192.168.0.10:9090/api/v1/"
    }
}
