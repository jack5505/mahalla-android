package uz.mahalla.data.prefs

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
import uz.mahalla.core.locale.AppLanguage
import java.io.File
import java.io.IOException

/**
 * DataStore (эпик 1.4): дефолты, запись и чтение языка, темы и флага
 * онбординга.
 *
 * На один файл в процессе допустим ровно один экземпляр DataStore, поэтому
 * внутри теста он создаётся один раз и переиспользуется.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SettingsDataStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun newDataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { File(temporaryFolder.root, "settings.preferences_pb") },
    )

    @Test
    fun `empty storage yields documented defaults`() = runTest {
        val settings = SettingsDataStore(newDataStore()).current()

        assertEquals(AppLanguage.SYSTEM, settings.language)
        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
        assertFalse(settings.onboardingCompleted)
        assertFalse(settings.pinConfigured)
    }

    @Test
    fun `language theme and onboarding flag survive a new instance`() = runTest {
        val dataStore = newDataStore()
        val store = SettingsDataStore(dataStore)

        store.setLanguage(AppLanguage.RUSSIAN)
        store.setThemeMode(ThemeMode.LIGHT)
        store.setOnboardingCompleted(true)

        val reloaded = SettingsDataStore(dataStore).current()
        assertEquals(AppLanguage.RUSSIAN, reloaded.language)
        assertEquals(ThemeMode.LIGHT, reloaded.themeMode)
        assertTrue(reloaded.onboardingCompleted)
    }

    @Test
    fun `system language is stored as an empty tag`() = runTest {
        val dataStore = newDataStore()
        val store = SettingsDataStore(dataStore)
        store.setLanguage(AppLanguage.UZBEK)

        store.setLanguage(AppLanguage.SYSTEM)

        assertEquals("", dataStore.data.first()[PreferenceKeys.Language])
        assertEquals(AppLanguage.SYSTEM, store.current().language)
    }

    @Test
    fun `settings flow emits every change`() = runTest {
        val store = SettingsDataStore(newDataStore())

        store.setThemeMode(ThemeMode.DARK)

        assertEquals(ThemeMode.DARK, store.settings.first().themeMode)
    }

    @Test
    fun `session round trips and is fully cleared`() = runTest {
        val sessionStore = DataStoreSessionStore(newDataStore())
        assertNull(sessionStore.current())

        sessionStore.save(Session("access-1", "refresh-1", expiresAtEpochSeconds = 4_600))
        assertEquals(Session("access-1", "refresh-1", 4_600), sessionStore.current())

        sessionStore.clear()
        assertNull(sessionStore.current())
    }

    @Test
    fun `pin counts as configured only with both hash and salt`() = runTest {
        val dataStore = newDataStore()
        val store = SettingsDataStore(dataStore)

        dataStore.edit { it[PreferenceKeys.PinHash] = "encrypted" }
        assertFalse("без соли хэш проверить нечем", store.current().pinConfigured)

        dataStore.edit { it[PreferenceKeys.PinSalt] = "salt" }
        assertTrue(store.current().pinConfigured)
    }

    /**
     * Файл настроек может быть недоступен (нет места, права, битый restore).
     * Раньше исключение улетало в `stateIn` внутри `viewModelScope` — краш на
     * старте, причём под splash'ем, который держится до первой эмиссии.
     */
    @Test
    fun `unreadable settings fall back to defaults instead of throwing`() = runTest {
        assertEquals(AppSettings(), SettingsDataStore(FailingDataStore()).current())
    }

    @Test
    fun `unreadable session reads as no session`() = runTest {
        assertNull(DataStoreSessionStore(FailingDataStore()).current())
    }

    @Test
    fun `unknown stored theme falls back to the default`() = runTest {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStoredValue("NEON"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStoredValue(null))
    }

    @Test
    fun `theme mode resolves darkness against the system setting`() {
        assertTrue(ThemeMode.SYSTEM.isDark(systemInDarkTheme = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemInDarkTheme = false))
        assertTrue(ThemeMode.DARK.isDark(systemInDarkTheme = false))
        assertFalse(ThemeMode.LIGHT.isDark(systemInDarkTheme = true))
    }

    /** DataStore, у которого чтение и запись всегда падают. */
    private class FailingDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IOException("storage is gone") }

        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences,
        ): Preferences = throw IOException("storage is gone")
    }
}
