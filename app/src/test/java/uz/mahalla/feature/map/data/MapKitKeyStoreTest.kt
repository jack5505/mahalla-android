package uz.mahalla.feature.map.data

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.data.prefs.SettingsDataStore
import java.io.File

/**
 * Ключ MapKit, введённый в приложении (issue #129).
 *
 * Проверяется то, из-за чего задача и появилась: сборка без секрета оставляет
 * карту мёртвой, и единственный способ её оживить — ключ, введённый руками,
 * который переживает перезапуск и действует немедленно.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MapKitKeyStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun newDataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { File(temporaryFolder.root, "settings.preferences_pb") },
    )

    private fun store(
        dataStore: DataStore<Preferences> = newDataStore(),
        buildKey: String = "",
        canEdit: Boolean = true,
    ) = MapKitKeyStore(
        settingsDataStore = SettingsDataStore(dataStore),
        buildKey = buildKey,
        canEdit = canEdit,
    )

    @Test
    fun `without a saved key the build key is used`() = runTest {
        assertEquals("build-key", store(buildKey = "build-key").current())
    }

    @Test
    fun `build without a key has no key at all`() = runTest {
        assertEquals("", store().current())
        assertNull(store().saved())
    }

    @Test
    fun `saved key wins over the build key and survives a new instance`() = runTest {
        val dataStore = newDataStore()

        assertTrue(store(dataStore, buildKey = "build-key").save("  user-key  "))

        val fresh = store(dataStore, buildKey = "build-key")
        assertEquals("user-key", fresh.current())
        assertEquals("user-key", fresh.saved())
    }

    /** Ключ обязан действовать сразу: перезапускать приложение ради карты незачем. */
    @Test
    fun `saved key applies to the same instance immediately`() = runTest {
        val store = store()

        assertEquals("", store.current())
        store.save("user-key")

        assertEquals("user-key", store.current())
    }

    /** Пустое поле — «убрать свой ключ», а не «ключ из пробелов». */
    @Test
    fun `empty key returns the build key back`() = runTest {
        val dataStore = newDataStore()
        val store = store(dataStore, buildKey = "build-key")
        store.save("user-key")

        store.save("   ")

        assertEquals("build-key", store.current())
        assertNull(store(dataStore, buildKey = "build-key").saved())
    }

    /**
     * Сборка без права менять конфигурацию (release без `BACKEND_URL_OVERRIDE`)
     * не читает сохранённый ключ вовсе: ключ из debug-установки не должен
     * переезжать в релиз через бэкап настроек — то же правило, что у адреса
     * бэкенда (issue #26).
     */
    @Test
    fun `build without the right ignores both saved key and writes`() = runTest {
        val dataStore = newDataStore()
        store(dataStore, buildKey = "build-key").save("user-key")

        val locked = store(dataStore, buildKey = "build-key", canEdit = false)

        assertFalse(locked.save("another-key"))
        assertEquals("build-key", locked.current())
        assertNull(locked.saved())
    }
}
