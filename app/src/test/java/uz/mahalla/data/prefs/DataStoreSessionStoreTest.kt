package uz.mahalla.data.prefs

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.testutil.FakeSessionCipher
import java.io.File

/**
 * Шифрование токенов сессии (issue #319). Keystore недоступен на JVM, поэтому
 * шифр подменён — проверяется обвязка: что в файле нет исходных токенов, что
 * потеря ключа не запирает человека вне приложения, и что токены, записанные
 * до этой задачи (открытым текстом), продолжают читаться.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DataStoreSessionStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun dataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { File(temporaryFolder.root, "session.preferences_pb") },
    )

    @Test
    fun `nothing resembling the tokens reaches the storage`() = runTest {
        val store = dataStore()
        DataStoreSessionStore(store, FakeSessionCipher())
            .save(Session("access-1", "refresh-1", 4_600))

        val preferences = store.data.first()
        assertNotEquals("access-1", preferences[PreferenceKeys.SessionAccessToken])
        assertNotEquals("refresh-1", preferences[PreferenceKeys.SessionRefreshToken])
    }

    @Test
    fun `session round trips through encryption`() = runTest {
        val store = dataStore()
        val sessionStore = DataStoreSessionStore(store, FakeSessionCipher())

        sessionStore.save(Session("access-1", "refresh-1", 4_600, sessionId = "s-1"))

        assertEquals(Session("access-1", "refresh-1", 4_600, "s-1"), sessionStore.current())
    }

    /**
     * Токены, сохранённые версией приложения до этой задачи, лежат открытым
     * текстом под теми же ключами. Обновление не должно разлогинить всех
     * разом — старое значение обязано читаться как есть, а не как «нет сессии».
     */
    @Test
    fun `plaintext tokens saved before encryption still read back`() = runTest {
        val store = dataStore()
        store.edit { preferences ->
            preferences[PreferenceKeys.SessionAccessToken] = "legacy-access"
            preferences[PreferenceKeys.SessionRefreshToken] = "legacy-refresh"
            preferences[PreferenceKeys.SessionExpiresAt] = 100L
        }

        val session = DataStoreSessionStore(store, FakeSessionCipher()).current()

        assertEquals("legacy-access", session?.accessToken)
        assertEquals("legacy-refresh", session?.refreshToken)
    }

    /**
     * После первого же обновления токенов (обычный refresh по 401 или новый
     * вход) старая запись должна стать зашифрованной, а не остаться открытым
     * текстом навсегда.
     */
    @Test
    fun `the next save migrates a legacy plaintext session to encryption`() = runTest {
        val store = dataStore()
        store.edit { preferences ->
            preferences[PreferenceKeys.SessionAccessToken] = "legacy-access"
            preferences[PreferenceKeys.SessionRefreshToken] = "legacy-refresh"
        }
        val sessionStore = DataStoreSessionStore(store, FakeSessionCipher())

        sessionStore.save(Session("access-2", "refresh-2"))

        val preferences = store.data.first()
        assertNotEquals("access-2", preferences[PreferenceKeys.SessionAccessToken])
        assertEquals("access-2", sessionStore.current()?.accessToken)
    }

    /**
     * Отказ Keystore (ключ потерян, устройство сброшено) не должен крэшить
     * приложение — тот же принцип, что и у PIN (ADR 0013). Токен возвращается
     * как есть, дальше решает сеть: 401 запустит обычный refresh или выход.
     */
    @Test
    fun `lost keystore key returns the raw payload instead of crashing`() = runTest {
        val store = dataStore()
        DataStoreSessionStore(store, FakeSessionCipher()).save(Session("access-1", "refresh-1"))
        val storedAccessToken = store.data.first()[PreferenceKeys.SessionAccessToken]

        val afterKeyLoss = DataStoreSessionStore(store, FakeSessionCipher(available = false))

        // Сессия обязана остаться живой (не null): иначе `TokenAuthenticator`
        // вышел бы из `authenticate` раньше `sessionStore.clear()` и
        // `sessionExpiry.notifyExpired()`, и человек застрял бы на экране без
        // сети и без выхода — то самое запирание, которого не должно быть.
        val session = afterKeyLoss.current()
        assertNotEquals("access-1", session?.accessToken)
        assertEquals(storedAccessToken, session?.accessToken)
    }

    @Test
    fun `clear removes both tokens`() = runTest {
        val store = dataStore()
        val sessionStore = DataStoreSessionStore(store, FakeSessionCipher())
        sessionStore.save(Session("access-1", "refresh-1"))

        sessionStore.clear()

        assertNull(sessionStore.current())
    }

    @Test
    fun `corrupted base64 does not crash reading`() = runTest {
        val store = dataStore()
        store.edit { preferences ->
            preferences[PreferenceKeys.SessionAccessToken] = "not-base64!!!"
            preferences[PreferenceKeys.SessionRefreshToken] = "not-base64!!!"
        }

        val session = DataStoreSessionStore(store, FakeSessionCipher()).current()

        assertEquals("not-base64!!!", session?.accessToken)
    }
}
