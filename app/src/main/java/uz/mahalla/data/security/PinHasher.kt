package uz.mahalla.data.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Хэширование PIN (эпик 1.4).
 *
 * PIN короткий, поэтому обычный SHA перебирается мгновенно — нужен
 * медленный KDF с солью: PBKDF2-HMAC-SHA256. Сам PIN никуда не пишется,
 * в хранилище уходит только хэш (дополнительно зашифрованный ключом из
 * Keystore, см. [PinCipher]).
 *
 * Чистый JVM-код без зависимостей от Android — покрывается unit-тестами.
 */
object PinHasher {

    const val SALT_LENGTH_BYTES = 16
    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    fun newSalt(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(SALT_LENGTH_BYTES).also(random::nextBytes)

    fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Сравнение за постоянное время — иначе PIN подбирается по таймингам. */
    fun verify(pin: String, salt: ByteArray, expectedHash: ByteArray): Boolean =
        MessageDigest.isEqual(hash(pin, salt), expectedHash)
}
