package uz.mahalla.feature.discovery.data

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.feature.discovery.domain.SearchHistory
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * История поиска на настоящем DataStore (эпик 4.3).
 *
 * Правила порядка и дублей проверены отдельно в `SearchHistoryTest`; здесь
 * важно, что они переживают запись на диск и повторное чтение — а именно
 * сериализация в одну строку и была тем местом, где порядок терялся у
 * `stringSetPreferencesKey`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SearchHistoryStoreTest {

    @Test
    fun `history survives a rewrite and keeps the order`() = runTest {
        val store = DataStoreSearchHistoryStore(dataStore())

        store.add("osh")
        store.add("dorixona")
        store.add("kino")

        assertEquals(listOf("kino", "dorixona", "osh"), store.queries.first())
    }

    @Test
    fun `a repeated query moves to the top without duplicating`() = runTest {
        val store = DataStoreSearchHistoryStore(dataStore())
        store.add("osh")
        store.add("kino")

        store.add("OSH")

        // Регистр берётся от последнего ввода: пользователь только что набрал
        // именно так, и подменять его прошлым написанием незачем.
        assertEquals(listOf("OSH", "kino"), store.queries.first())
    }

    @Test
    fun `remove and clear reach the storage`() = runTest {
        val store = DataStoreSearchHistoryStore(dataStore())
        store.add("osh")
        store.add("kino")

        store.remove("osh")
        assertEquals(listOf("kino"), store.queries.first())

        store.clear()
        assertTrue(store.queries.first().isEmpty())
    }

    @Test
    fun `stored history never grows past the cap`() = runTest {
        val store = DataStoreSearchHistoryStore(dataStore())

        repeat(SearchHistory.MAX_SIZE + 5) { store.add("q$it") }

        assertEquals(SearchHistory.MAX_SIZE, store.queries.first().size)
    }

    @Test
    fun `empty storage reads as an empty history`() = runTest {
        assertTrue(DataStoreSearchHistoryStore(dataStore()).queries.first().isEmpty())
    }

    /**
     * Свой файл на каждый тест: DataStore допускает один экземпляр на файл в
     * процессе, а методы одного класса Robolectric делят JVM.
     */
    private fun dataStore(): DataStore<Preferences> {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = File(context.filesDir, "search_history_${counter.incrementAndGet()}.preferences_pb")
        return PreferenceDataStoreFactory.create(produceFile = { file })
    }

    private companion object {
        val counter = AtomicInteger(0)
    }
}
