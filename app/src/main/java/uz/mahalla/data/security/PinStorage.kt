package uz.mahalla.data.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import uz.mahalla.data.prefs.PreferenceKeys
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** Локальный PIN: только установка и проверка, прочитать его нельзя. */
interface PinStorage {
    suspend fun isConfigured(): Boolean
    suspend fun save(pin: String)
    suspend fun verify(pin: String): Boolean
    suspend fun clear()
}

/**
 * PIN в DataStore (эпик 1.4): соль в открытом виде, хэш — зашифрован ключом
 * из Keystore. Даже с root-доступом к файлу настроек хэш без ключа устройства
 * бесполезен.
 */
@Singleton
class KeystorePinStorage @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val cipher: PinCipher,
) : PinStorage {

    override suspend fun isConfigured(): Boolean {
        val preferences = dataStore.data.first()
        return preferences[PreferenceKeys.PinHash] != null &&
            preferences[PreferenceKeys.PinSalt] != null
    }

    override suspend fun save(pin: String) {
        val salt = PinHasher.newSalt()
        val encryptedHash = cipher.encrypt(PinHasher.hash(pin, salt))
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.PinSalt] = salt.toBase64()
            preferences[PreferenceKeys.PinHash] = encryptedHash.toBase64()
        }
    }

    override suspend fun verify(pin: String): Boolean {
        val preferences = dataStore.data.first()
        val salt = preferences[PreferenceKeys.PinSalt]?.fromBase64() ?: return false
        val encryptedHash = preferences[PreferenceKeys.PinHash]?.fromBase64() ?: return false
        // Ключ мог быть потерян (сброс биометрии, восстановление из бэкапа) —
        // это не крэш, а «PIN не подходит», пользователь войдёт по SMS.
        val storedHash = runCatching { cipher.decrypt(encryptedHash) }.getOrNull() ?: return false
        return PinHasher.verify(pin, salt, storedHash)
    }

    override suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(PreferenceKeys.PinHash)
            preferences.remove(PreferenceKeys.PinSalt)
        }
    }

    private fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)

    private fun String.fromBase64(): ByteArray? =
        runCatching { Base64.getDecoder().decode(this) }.getOrNull()
}
