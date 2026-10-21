package uz.mahalla.testutil

import uz.mahalla.data.security.SessionCipher

/**
 * Обратимое «шифрование» без Keystore (issue #319) — как у PIN
 * (`KeystorePinStorageTest.ReversibleCipher`), но для сессии: проверяется
 * обвязка `DataStoreSessionStore`, а не сам AES/GCM.
 */
class FakeSessionCipher(private val available: Boolean = true) : SessionCipher {

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
