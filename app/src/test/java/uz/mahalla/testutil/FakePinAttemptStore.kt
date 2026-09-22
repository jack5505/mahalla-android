package uz.mahalla.testutil

import uz.mahalla.data.security.PinAttemptStore

/**
 * Счётчик неверных попыток в памяти. Настоящий живёт в DataStore и потому
 * требует Robolectric — его собственное поведение проверяет
 * `PinAttemptStoreTest`, а здесь важно только то, что значение переживает
 * пересоздание ViewModel.
 *
 * @param initial сколько попыток уже потрачено до создания экрана — так
 * выглядит перезапуск приложения посреди подбора кода.
 */
class FakePinAttemptStore(initial: Int = 0) : PinAttemptStore {

    var failed: Int = initial
        private set

    var resetCount: Int = 0
        private set

    /** Отказ DataStore: приложение обязано его пережить, а не запереться. */
    var failure: Exception? = null

    override suspend fun failedAttempts(): Int {
        failure?.let { throw it }
        return failed
    }

    override suspend fun recordFailure(): Int {
        failure?.let { throw it }
        failed++
        return failed
    }

    override suspend fun reset() {
        failure?.let { throw it }
        failed = 0
        resetCount++
    }
}
