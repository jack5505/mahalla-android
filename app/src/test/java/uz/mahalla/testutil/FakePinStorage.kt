package uz.mahalla.testutil

import uz.mahalla.data.security.PinStorage

/**
 * PIN в памяти. Настоящий [PinStorage] — это PBKDF2 на 120 000 итераций плюс
 * Keystore: в тестах ViewModel это только медленно и требует Robolectric.
 */
class FakePinStorage(initialPin: String? = null) : PinStorage {

    var storedPin: String? = initialPin
        private set

    var saveCount: Int = 0
        private set

    var clearCount: Int = 0
        private set

    override suspend fun isConfigured(): Boolean = storedPin != null

    override suspend fun save(pin: String) {
        storedPin = pin
        saveCount++
    }

    override suspend fun verify(pin: String): Boolean = storedPin != null && storedPin == pin

    override suspend fun clear() {
        storedPin = null
        clearCount++
    }
}
