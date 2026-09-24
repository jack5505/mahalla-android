package uz.mahalla.testutil

import androidx.biometric.BiometricPrompt
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import uz.mahalla.data.security.BiometricCipher

/**
 * Ключ биометрии в памяти (issue #318): не трогает AndroidKeyStore — тесты
 * идут на чистом JVM. `CryptoObject` собран через настоящий `javax.crypto`
 * (`SunJCE`, `AES/CBC`, не `AES/GCM` прода) просто чтобы дать промпту непустой
 * `Cipher` — сам `doFinal` фейк не зовёт вовсе, успех/провал задаётся полями
 * ниже, а не шифром.
 *
 * [verificationGate] — точка, где `completeVerification` можно придержать
 * (например, `CompletableDeferred`), чтобы в тесте застать состояние ровно
 * между «промпт подтвердил» и «крипто-операция ответила».
 */
class FakeBiometricCipher(
    var enrollmentSucceeds: Boolean = true,
    var verificationAvailable: Boolean = true,
    var verificationSucceeds: Boolean = true,
    private val verificationGate: suspend () -> Unit = {},
) : BiometricCipher {

    var cleared: Boolean = false
        private set

    override suspend fun prepareEnrollment(): BiometricPrompt.CryptoObject? =
        if (enrollmentSucceeds) fakeCryptoObject() else null

    override suspend fun completeEnrollment(cryptoObject: BiometricPrompt.CryptoObject): Boolean =
        enrollmentSucceeds

    override suspend fun prepareVerification(): BiometricPrompt.CryptoObject? =
        if (verificationAvailable) fakeCryptoObject() else null

    override suspend fun completeVerification(cryptoObject: BiometricPrompt.CryptoObject): Boolean {
        verificationGate()
        return verificationSucceeds
    }

    override suspend fun clear() {
        cleared = true
    }

    companion object {
        fun fakeCryptoObject(): BiometricPrompt.CryptoObject {
            val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            return BiometricPrompt.CryptoObject(cipher)
        }
    }
}
