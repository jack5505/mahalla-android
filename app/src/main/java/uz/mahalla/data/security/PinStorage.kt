package uz.mahalla.data.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import uz.mahalla.data.prefs.PreferenceKeys
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** Локальный PIN: только установка и проверка, прочитать его нельзя. */
interface PinStorage {
    suspend fun isConfigured(): Boolean

    /**
     * Из скольких цифр состоит сохранённый PIN, `null` — не настроен.
     *
     * Длину пришлось запомнить, когда PIN стал шестизначным (issue #51):
     * иначе экран блокировки нарисовал бы шесть ячеек человеку с прежним
     * четырёхзначным кодом, и ввести его стало бы нечем. Сам код по этому
     * значению не восстанавливается.
     */
    suspend fun configuredLength(): Int?

    suspend fun save(pin: String)
    suspend fun verify(pin: String): Boolean
    suspend fun clear()
}

/**
 * PIN в DataStore (эпик 1.4): соль в открытом виде, хэш — зашифрован ключом
 * из Keystore. Даже с root-доступом к файлу настроек хэш без ключа устройства
 * бесполезен.
 *
 * PBKDF2 (120 000 итераций) и обращения к Keystore уходят на [Dispatchers.Default]:
 * вызов приходит из `viewModelScope` (Main), а на слабых устройствах это сотни
 * миллисекунд — на главном потоке это фриз ввода PIN и риск ANR.
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

    /**
     * PIN настроен, а длины нет — значит он записан прошлой версией
     * приложения, до issue #51: тогда PIN был четырёхзначным.
     */
    override suspend fun configuredLength(): Int? {
        val preferences = dataStore.data.first()
        if (preferences[PreferenceKeys.PinHash] == null ||
            preferences[PreferenceKeys.PinSalt] == null
        ) {
            return null
        }
        return preferences[PreferenceKeys.PinLength] ?: LEGACY_PIN_LENGTH
    }

    override suspend fun save(pin: String) {
        val salt = PinHasher.newSalt()
        val encryptedHash = withContext(Dispatchers.Default) {
            cipher.encrypt(PinHasher.hash(pin, salt))
        }
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.PinSalt] = salt.toBase64()
            preferences[PreferenceKeys.PinHash] = encryptedHash.toBase64()
            preferences[PreferenceKeys.PinLength] = pin.length
        }
    }

    override suspend fun verify(pin: String): Boolean {
        val preferences = dataStore.data.first()
        val salt = preferences[PreferenceKeys.PinSalt]?.fromBase64() ?: return false
        val encryptedHash = preferences[PreferenceKeys.PinHash]?.fromBase64() ?: return false
        return withContext(Dispatchers.Default) {
            // Ключ мог быть потерян (сброс биометрии, восстановление из бэкапа) —
            // это не крэш, а «PIN не подходит», пользователь войдёт по SMS.
            val storedHash = runCatching { cipher.decrypt(encryptedHash) }.getOrNull()
                ?: return@withContext false
            PinHasher.verify(pin, salt, storedHash)
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(PreferenceKeys.PinHash)
            preferences.remove(PreferenceKeys.PinSalt)
            preferences.remove(PreferenceKeys.PinLength)
        }
    }

    private fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)

    private fun String.fromBase64(): ByteArray? =
        runCatching { Base64.getDecoder().decode(this) }.getOrNull()

    private companion object {
        const val LEGACY_PIN_LENGTH = 4
    }
}
