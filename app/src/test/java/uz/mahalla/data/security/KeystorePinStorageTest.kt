package uz.mahalla.data.security

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.rules.TemporaryFolder
import uz.mahalla.data.prefs.PreferenceKeys
import java.io.File
import java.util.Base64

/**
 * Логика хранения PIN (эпик 1.4). Keystore недоступен на JVM, поэтому шифр
 * подменён — проверяется именно хранилище: что в файл уходит не PIN, что
 * сравнение работает и что потеря ключа не крэшит приложение.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class KeystorePinStorageTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** Обратимое «шифрование»: достаточно, чтобы проверить обвязку. */
    private class ReversibleCipher(private val available: Boolean = true) : PinCipher {
        override fun encrypt(plain: ByteArray): ByteArray =
            ByteArray(plain.size) { (plain[it].toInt() xor MASK).toByte() }

        override fun decrypt(payload: ByteArray): ByteArray {
            check(available) { "ключ недоступен" }
            return ByteArray(payload.size) { (payload[it].toInt() xor MASK).toByte() }
        }

        private companion object {
            const val MASK = 0x5A
        }
    }

    private fun dataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { File(temporaryFolder.root, "pin.preferences_pb") },
    )

    @Test
    fun `pin is not configured until it is saved`() = runTest {
        val storage = KeystorePinStorage(dataStore(), ReversibleCipher())

        assertFalse(storage.isConfigured())

        storage.save("1234")

        assertTrue(storage.isConfigured())
    }

    @Test
    fun `verify accepts the saved pin and rejects any other`() = runTest {
        val storage = KeystorePinStorage(dataStore(), ReversibleCipher())
        storage.save("1234")

        assertTrue(storage.verify("1234"))
        assertFalse(storage.verify("4321"))
    }

    @Test
    fun `nothing resembling the pin reaches the storage`() = runTest {
        val store = dataStore()
        KeystorePinStorage(store, ReversibleCipher()).save("1234")

        val preferences = store.data.first()
        val storedHash = preferences[PreferenceKeys.PinHash]
        val storedSalt = preferences[PreferenceKeys.PinSalt]
        assertNotEquals("1234", storedHash)
        assertTrue(storedSalt.orEmpty().isNotEmpty())

        // В файле лежит именно зашифрованный PBKDF2-хэш от сохранённой соли.
        val decoder = Base64.getDecoder()
        assertEquals(
            PinHasher.hash("1234", decoder.decode(storedSalt)).toList(),
            ReversibleCipher().decrypt(decoder.decode(storedHash)).toList(),
        )
    }

    @Test
    fun `every save uses a fresh salt`() = runTest {
        val store = dataStore()
        val storage = KeystorePinStorage(store, ReversibleCipher())

        storage.save("1234")
        val firstSalt = store.data.first()[PreferenceKeys.PinSalt]
        storage.save("1234")
        val secondSalt = store.data.first()[PreferenceKeys.PinSalt]

        assertNotEquals(firstSalt, secondSalt)
        assertTrue(storage.verify("1234"))
    }

    @Test
    fun `verify returns false when there is no pin at all`() = runTest {
        assertFalse(KeystorePinStorage(dataStore(), ReversibleCipher()).verify("1234"))
    }

    @Test
    fun `lost keystore key means wrong pin, not a crash`() = runTest {
        val store = dataStore()
        KeystorePinStorage(store, ReversibleCipher()).save("1234")

        val afterKeyLoss = KeystorePinStorage(store, ReversibleCipher(available = false))

        assertFalse(afterKeyLoss.verify("1234"))
    }

    @Test
    fun `clear removes both the hash and the salt`() = runTest {
        val store = dataStore()
        val storage = KeystorePinStorage(store, ReversibleCipher())
        storage.save("1234")

        storage.clear()

        assertFalse(storage.isConfigured())
        assertEquals(null, store.data.first()[PreferenceKeys.PinHash])
        assertEquals(null, store.data.first()[PreferenceKeys.PinSalt])
    }

    @Test
    fun `the saved length is remembered and cleared with the pin`() = runTest {
        val storage = KeystorePinStorage(dataStore(), ReversibleCipher())

        assertEquals(null, storage.configuredLength())

        storage.save("123456")
        assertEquals(6, storage.configuredLength())

        storage.clear()
        assertEquals(null, storage.configuredLength())
    }

    @Test
    fun `a pin saved before the length was stored counts as four digits`() = runTest {
        val store = dataStore()
        KeystorePinStorage(store, ReversibleCipher()).save("1234")
        // Записи прошлой версии приложения ключа длины не имеют (issue #51).
        store.edit { it.remove(PreferenceKeys.PinLength) }

        assertEquals(4, KeystorePinStorage(store, ReversibleCipher()).configuredLength())
    }

    @Test
    fun `corrupted base64 does not crash verification`() = runTest {
        val store = dataStore()
        store.edit { preferences ->
            preferences[PreferenceKeys.PinSalt] = "not-base64!!!"
            preferences[PreferenceKeys.PinHash] = "not-base64!!!"
        }

        assertFalse(KeystorePinStorage(store, ReversibleCipher()).verify("1234"))
    }
}
