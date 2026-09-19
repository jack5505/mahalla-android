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

    /**
     * Отказ хранилища. Настоящий Keystore кидает `KeyStoreException` и
     * `KeyPermanentlyInvalidatedException` — приложение обязано это пережить.
     */
    var failure: Exception? = null

    /**
     * Отказ только записи. Нужен там, где проверяется уборка после неудачного
     * [save]: общий [failure] уронил бы и её саму.
     */
    var saveFailure: Exception? = null

    override suspend fun isConfigured(): Boolean {
        failure?.let { throw it }
        return storedPin != null
    }

    override suspend fun configuredLength(): Int? {
        failure?.let { throw it }
        return storedPin?.length
    }

    override suspend fun save(pin: String) {
        failure?.let { throw it }
        saveFailure?.let { throw it }
        storedPin = pin
        saveCount++
    }

    override suspend fun verify(pin: String): Boolean {
        failure?.let { throw it }
        return storedPin != null && storedPin == pin
    }

    override suspend fun clear() {
        failure?.let { throw it }
        storedPin = null
        clearCount++
    }
}
