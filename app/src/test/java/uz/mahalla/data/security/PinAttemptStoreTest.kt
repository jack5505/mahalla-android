package uz.mahalla.data.security

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Счётчик неверных попыток на настоящем DataStore (issue #102, ADR 0004).
 *
 * Ради чего он вообще переехал из памяти: значение обязано пережить
 * перезапуск процесса. Поэтому здесь проверяется именно чтение **новым**
 * экземпляром по тому же файлу — фейк в тестах ViewModel этого показать не
 * может.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PinAttemptStoreTest {

    @Test
    fun `empty storage reads as no attempts spent`() = runTest {
        assertEquals(0, DataStorePinAttemptStore(dataStore()).failedAttempts())
    }

    @Test
    fun `failures accumulate and are returned as they are recorded`() = runTest {
        val store = DataStorePinAttemptStore(dataStore())

        assertEquals(1, store.recordFailure())
        assertEquals(2, store.recordFailure())
        assertEquals(3, store.recordFailure())
        assertEquals(3, store.failedAttempts())
    }

    @Test
    fun `a new instance reads what the previous one recorded`() = runTest {
        val dataStore = dataStore()
        DataStorePinAttemptStore(dataStore).recordFailure()
        DataStorePinAttemptStore(dataStore).recordFailure()

        // Своего состояния у класса нет — всё значение лежит в DataStore. Это
        // и есть причина переезда: пересоздание не возвращает попытки.
        assertEquals(2, DataStorePinAttemptStore(dataStore).failedAttempts())
    }

    @Test
    fun `reset brings the counter back to zero`() = runTest {
        val store = DataStorePinAttemptStore(dataStore())
        store.recordFailure()
        store.recordFailure()

        store.reset()

        assertEquals(0, store.failedAttempts())
    }

    @Test
    fun `reset on an untouched storage is not an error`() = runTest {
        val store = DataStorePinAttemptStore(dataStore())

        // Успешная разблокировка обнуляет счётчик всегда, не спрашивая, был ли
        // он вообще записан.
        store.reset()

        assertEquals(0, store.failedAttempts())
    }

    /**
     * Свой файл на каждый тест: DataStore допускает один экземпляр на файл в
     * процессе, а методы одного класса Robolectric делят JVM.
     */
    private fun dataStore(): DataStore<Preferences> {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = File(context.filesDir, "pin_attempts_${counter.incrementAndGet()}.preferences_pb")
        return PreferenceDataStoreFactory.create(produceFile = { file })
    }

    private companion object {
        val counter = AtomicInteger(0)
    }
}
