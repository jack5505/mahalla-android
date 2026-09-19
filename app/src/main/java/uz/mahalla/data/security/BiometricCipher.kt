package uz.mahalla.data.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricPrompt
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import uz.mahalla.data.prefs.PreferenceKeys
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ключ Keystore, привязанный к биометрии (issue #318).
 *
 * В отличие от [PinCipher] ключ доступен только через `CryptoObject`
 * `BiometricPrompt`: `setUserAuthenticationRequired` требует свежий успешный
 * скан STRONG-датчика на **каждую** операцию. Успех колбэка
 * `onAuthenticationSucceeded` сам по себе ничего не доказывает — это просто
 * «система сказала да»; доказывает только то, что `doFinal` не бросил
 * исключение, то есть ключ действительно был разблокирован.
 *
 * Хранит один и тот же зашифрованный маркер — не секрет, его содержимое не
 * важно, важен только сам факт успешной расшифровки: GCM аутентифицирует тег,
 * и чужой или инвалидированный ключ бросит исключение раньше, чем отдаст
 * неверный результат молча.
 */
interface BiometricCipher {

    /**
     * `CryptoObject` для промпта, которым включают вход по биометрии
     * (онбординг 3.5, настройки, профиль). Создаёт ключ Keystore, если его
     * ещё нет. `null` — Keystore недоступен, включать нечем.
     */
    suspend fun prepareEnrollment(): BiometricPrompt.CryptoObject?

    /**
     * Прогнать шифрование маркера через уже подтверждённый датчиком
     * `CryptoObject` и сохранить результат. `false` — крипто-операция не
     * прошла, включать биометрию нельзя, несмотря на успешный промпт.
     */
    suspend fun completeEnrollment(cryptoObject: BiometricPrompt.CryptoObject): Boolean

    /**
     * `CryptoObject` для промпта разблокировки/оплаты. `null` — секрета нет
     * или ключ инвалидирован (новый отпечаток, сброс биометрии на устройстве
     * между включением и этим запуском): вызывающий обязан не показывать
     * системный промпт вовсе и вести себя так, будто биометрия недоступна —
     * PIN остаётся входом.
     */
    suspend fun prepareVerification(): BiometricPrompt.CryptoObject?

    /**
     * Прогнать расшифровку маркера через уже подтверждённый датчиком
     * `CryptoObject`. `false` — операция не прошла, разблокировку/оплату
     * подтверждать нечем.
     */
    suspend fun completeVerification(cryptoObject: BiometricPrompt.CryptoObject): Boolean

    /** Забыть секрет и ключ — биометрию выключили на любом из экранов. */
    suspend fun clear()
}

@Singleton
class AndroidBiometricCipher @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : BiometricCipher {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
    }

    override suspend fun prepareEnrollment(): BiometricPrompt.CryptoObject? =
        withContext(Dispatchers.Default) {
            runCatching {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, generateKey())
                BiometricPrompt.CryptoObject(cipher)
            }.getOrNull()
        }

    override suspend fun completeEnrollment(cryptoObject: BiometricPrompt.CryptoObject): Boolean {
        val cipher = cryptoObject.cipher ?: return false
        val encrypted = withContext(Dispatchers.Default) {
            runCatching { cipher.doFinal(MARKER) }.getOrNull()
        } ?: return false
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.BiometricSecretIv] = cipher.iv.toBase64()
            preferences[PreferenceKeys.BiometricSecretPayload] = encrypted.toBase64()
        }
        return true
    }

    override suspend fun prepareVerification(): BiometricPrompt.CryptoObject? = runCatching {
        // DataStore и Keystore отказывают так же, как в `prepareEnrollment` —
        // весь метод, а не только `Cipher`, обязан быть под `runCatching`:
        // непойманный `KeyStoreException`/`IOException` здесь уронил бы
        // экран блокировки в краш-петлю на каждом появлении оверлея.
        val preferences = dataStore.data.first()
        val iv = preferences[PreferenceKeys.BiometricSecretIv]?.fromBase64() ?: return@runCatching null
        if (preferences[PreferenceKeys.BiometricSecretPayload] == null) return@runCatching null
        val key = existingKey() ?: return@runCatching null
        withContext(Dispatchers.Default) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
            BiometricPrompt.CryptoObject(cipher)
        }
    }.getOrNull()

    override suspend fun completeVerification(cryptoObject: BiometricPrompt.CryptoObject): Boolean =
        runCatching {
            val cipher = cryptoObject.cipher ?: return@runCatching false
            val payload = dataStore.data.first()[PreferenceKeys.BiometricSecretPayload]?.fromBase64()
                ?: return@runCatching false
            withContext(Dispatchers.Default) { cipher.doFinal(payload) }
            true
        }.getOrDefault(false)

    override suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(PreferenceKeys.BiometricSecretIv)
            preferences.remove(PreferenceKeys.BiometricSecretPayload)
        }
        runCatching { keyStore.deleteEntry(KEY_ALIAS) }
    }

    private fun existingKey(): SecretKey? =
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun generateKey(): SecretKey {
        existingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setUserAuthenticationRequired(true)
                .apply {
                    // На API < 30 у Keystore нет отдельного параметра для
                    // класса биометрии, но auth-per-operation (в отличие от
                    // duration-based) сам по себе доступен только STRONG-классу
                    // — слабее (weak/convenience) с CryptoObject не работает.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    } else {
                        @Suppress("DEPRECATION")
                        setUserAuthenticationValidityDurationSeconds(-1)
                    }
                }
                // Новый отпечаток/лицо — новый хозяин ключа, пока не доказано
                // обратное: старый ключ обязан перестать открываться.
                .setInvalidatedByBiometricEnrollment(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)

    private fun String.fromBase64(): ByteArray? =
        runCatching { Base64.getDecoder().decode(this) }.getOrNull()

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "uz.mahalla.biometric_unlock"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
        const val KEY_SIZE_BITS = 256
        val MARKER = "uz.mahalla.biometric.verified".toByteArray()
    }
}
