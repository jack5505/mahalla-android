package uz.mahalla.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Шифрование токенов сессии ключом, который не покидает устройство
 * (issue #319). Вынесено за интерфейс по той же причине, что и [PinCipher]:
 * Keystore недоступен в JVM-тестах.
 */
interface SessionCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(payload: ByteArray): ByteArray
}

/**
 * AES-256/GCM на отдельном ключе из AndroidKeyStore — свой алиас, а не
 * [AndroidKeystorePinCipher]: PIN и токены сессии теряются по разным поводам
 * (смена кода против выхода из аккаунта), и общий ключ связал бы их отказ без
 * причины. IV случайный на каждое шифрование и пишется в начало payload'а,
 * как и у PIN.
 *
 * `by lazy` намеренно: конструктор не должен трогать Keystore — граф собирается
 * и в тестах, и на эмуляторах без аппаратного хранилища.
 */
@Singleton
class AndroidKeystoreSessionCipher @Inject constructor() : SessionCipher {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plain)
        return cipher.iv + encrypted
    }

    override fun decrypt(payload: ByteArray): ByteArray {
        require(payload.size > IV_LENGTH_BYTES) { "payload короче IV" }
        val iv = payload.copyOfRange(0, IV_LENGTH_BYTES)
        val encrypted = payload.copyOfRange(IV_LENGTH_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return cipher.doFinal(encrypted)
    }

    private fun secretKey(): SecretKey {
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        return existing?.secretKey ?: generateKey()
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "uz.mahalla.session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH_BYTES = 12
        const val TAG_LENGTH_BITS = 128
        const val KEY_SIZE_BITS = 256
    }
}
