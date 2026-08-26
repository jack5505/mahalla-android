package uz.mahalla.feature.discovery.ui.search

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.discovery.domain.DiscoveryFilters
import uz.mahalla.feature.discovery.domain.Place
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.discovery.domain.PlaceSort
import uz.mahalla.testutil.FakeCatalogRepository
import uz.mahalla.testutil.FakeSearchHistoryStore
import uz.mahalla.testutil.MainDispatcherRule
import uz.mahalla.testutil.place

/**
 * Поиск (эпик 4.3): запрос с задержкой, фильтры, история и пагинация.
 *
 * Диспетчер здесь [StandardTestDispatcher], а не unconfined: смысл debounce
 * в том, что запрос уходит не сразу, и проверить это можно только управляя
 * временем вручную.
 *
 * Robolectric нужен из-за `SavedStateHandle.toRoute()`: разбор типизированного
 * маршрута идёт через настоящий `Bundle`, а в обычном JVM-тесте android.jar
 * заглушен (`isReturnDefaultValues = true`) и все аргументы читались бы как
 * `null`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val repository = FakeCatalogRepository()
    private val history = FakeSearchHistoryStore()

    @Test
    fun `initial load runs without waiting for the debounce`() = runTest {
        repository.respondWith(listOf(place("p")))
        val viewModel = viewModel()

        advanceUntilIdle()

        assertTrue(viewModel.state.value.results is ScreenState.Content)
    }

    @Test
    fun `typing does not fire a request per keystroke`() = runTest {
        repository.respondWith(listOf(place("p")))
        val viewModel = viewModel()
        advanceUntilIdle()
        repository.requestedFilters.clear()

        viewModel.onEvent(SearchEvent.QueryChanged("o"))
        viewModel.onEvent(SearchEvent.QueryChanged("os"))
        viewModel.onEvent(SearchEvent.QueryChanged("osh"))
        advanceUntilIdle()

        assertEquals(1, repository.requestedFilters.size)
        assertEquals("osh", repository.requestedFilters.single().first.query)
    }

    @Test
    fun `query is visible immediately even though the request waits`() = runTest {
        repository.respondWith(listOf(place("p")))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onEvent(SearchEvent.QueryChanged("osh"))

        assertEquals("osh", viewModel.state.value.query)
        advanceTimeBy(DEBOUNCE_MS / 2)
        assertTrue("до истечения задержки запрос не ушёл", repository.requestedFilters.size == 1)
    }

    @Test
    fun `changing a filter searches immediately`() = runTest {
        // Выбор фильтра — осознанное действие, ждать его незачем.
        repository.respondWith(listOf(place("p")))
        val viewModel = viewModel()
        advanceUntilIdle()
        repository.requestedFilters.clear()

        viewModel.onEvent(SearchEvent.CategoryToggled(PlaceCategory.Cinema))
        advanceUntilIdle()

        assertEquals(
            setOf(PlaceCategory.Cinema),
            repository.requestedFilters.single().first.categories,
        )
    }

    @Test
    fun `every filter reaches the repository`() = runTest {
        repository.respondWith(listOf(place("p")))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onEvent(SearchEvent.DistanceSelected(1_000))
        viewModel.onEvent(SearchEvent.RatingSelected(4.0))
        viewModel.onEvent(SearchEvent.OpenNowToggled)
        viewModel.onEvent(SearchEvent.SortSelected(PlaceSort.Rating))
        advanceUntilIdle()

        val filters = repository.requestedFilters.last().first
        assertEquals(1_000, filters.maxDistanceMeters)
        assertEquals(4.0, filters.minRating!!, 1e-9)
        assertTrue(filters.openNowOnly)
        assertEquals(PlaceSort.Rating, filters.sort)
    }

    @Test
    fun `reset clears the filters but keeps the query`() = runTest {
        repository.respondWith(listOf(place("p")))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onEvent(SearchEvent.QueryChanged("osh"))
        viewModel.onEvent(SearchEvent.CategoryToggled(PlaceCategory.Food))
        advanceUntilIdle()

        viewModel.onEvent(SearchEvent.FiltersReset)
        advanceUntilIdle()

        assertEquals("osh", viewModel.state.value.query)
        assertEquals(0, viewModel.state.value.activeFilterCount)
    }

    @Test
    fun `category from the route is preselected`() = runTest {
        repository.respondWith(listOf(place("p")))

        val viewModel = viewModel(categoryId = "pharmacy")
        advanceUntilIdle()

        assertEquals(setOf(PlaceCategory.Pharmacy), viewModel.state.value.filters.categories)
    }

    @Test
    fun `an unknown category from the route does not become a filter`() = runTest {
        repository.respondWith(listOf(place("p")))

        val viewModel = viewModel(categoryId = "barbershop")
        advanceUntilIdle()

        assertTrue(viewModel.state.value.filters.categories.isEmpty())
    }

    @Test
    fun `submitting saves the query to the history`() = runTest {
        repository.respondWith(listOf(place("p")))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onEvent(SearchEvent.QueryChanged("osh"))
        viewModel.onEvent(SearchEvent.QuerySubmitted)
        advanceUntilIdle()

        assertEquals(listOf("osh"), history.current())
    }

    @Test
    fun `typing alone does not pollute the history`() = runTest {
        // Иначе в истории окажутся все обрывки набора: «o», «os», «osh».
        repository.respondWith(listOf(place("p")))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onEvent(SearchEvent.QueryChanged("os"))
        advanceUntilIdle()

        assertTrue(history.current().isEmpty())
    }

    @Test
    fun `history entry runs the search and moves to the top`() = runTest {
        repository.respondWith(listOf(place("p")))
        val viewModel = viewModel(history = FakeSearchHistoryStore(listOf("kino", "osh")))
        advanceUntilIdle()
        repository.requestedFilters.clear()

        viewModel.onEvent(SearchEvent.HistoryClicked("osh"))
        advanceUntilIdle()

        assertEquals("osh", repository.requestedFilters.single().first.query)
    }

    @Test
    fun `history is shown only while the query is empty`() = runTest {
        repository.respondWith(listOf(place("p")))
        val viewModel = viewModel(history = FakeSearchHistoryStore(listOf("osh")))
        advanceUntilIdle()

        assertTrue(viewModel.state.value.showHistory)

        viewModel.onEvent(SearchEvent.QueryChanged("kino"))
        advanceUntilIdle()

        assertFalse(viewModel.state.value.showHistory)
    }

    @Test
    fun `removing and clearing the history reach the store`() = runTest {
        repository.respondWith(listOf(place("p")))
        val store = FakeSearchHistoryStore(listOf("osh", "kino"))
        val viewModel = viewModel(history = store)
        advanceUntilIdle()

        viewModel.onEvent(SearchEvent.HistoryRemoved("osh"))
        advanceUntilIdle()
        assertEquals(listOf("kino"), store.current())

        viewModel.onEvent(SearchEvent.HistoryCleared)
        advanceUntilIdle()
        assertTrue(store.current().isEmpty())
    }

    @Test
    fun `empty answer becomes an empty state`() = runTest {
        repository.respondWith(emptyList())
        val viewModel = viewModel()

        advanceUntilIdle()

        assertEquals(ScreenState.Empty, viewModel.state.value.results)
    }

    @Test
    fun `next page is appended to the list`() = runTest {
        repository.respondWith(listOf(place("a"), place("b")), page = 0, hasMore = true)
        repository.respondWith(listOf(place("c")), page = 1, hasMore = false)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onEvent(SearchEvent.LoadMore)
        advanceUntilIdle()

        val results = viewModel.state.value.results as ScreenState.Content
        assertEquals(listOf("a", "b", "c"), results.data.map(Place::id))
        assertFalse(viewModel.state.value.hasMore)
        assertFalse(viewModel.state.value.isLoadingMore)
    }

    @Test
    fun `a place repeated on the next page is not duplicated`() = runTest {
        // Выдача могла пересортироваться между запросами; дубликат ключа
        // уронил бы LazyColumn.
        repository.respondWith(listOf(place("a"), place("b")), page = 0, hasMore = true)
        repository.respondWith(listOf(place("b"), place("c")), page = 1)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onEvent(SearchEvent.LoadMore)
        advanceUntilIdle()

        val results = viewModel.state.value.results as ScreenState.Content
        assertEquals(listOf("a", "b", "c"), results.data.map(Place::id))
    }

    @Test
    fun `load more is ignored when there is nothing left`() = runTest {
        repository.respondWith(listOf(place("a")), page = 0, hasMore = false)
        val viewModel = viewModel()
        advanceUntilIdle()
        repository.requestedFilters.clear()

        viewModel.onEvent(SearchEvent.LoadMore)
        advanceUntilIdle()

        assertTrue(repository.requestedFilters.isEmpty())
    }

    @Test
    fun `a failed next page does not erase the shown list`() = runTest {
        // Потерять сотню карточек из-за одного неудачного запроса хуже, чем
        // не получить следующую двадцатку.
        repository.respondWith(listOf(place("a")), page = 0, hasMore = true)
        repository.failWith(ApiError.NoConnection, page = 1)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onEvent(SearchEvent.LoadMore)
        advanceUntilIdle()

        val results = viewModel.state.value.results as ScreenState.Content
        assertEquals(listOf("a"), results.data.map(Place::id))
        assertFalse(viewModel.state.value.isLoadingMore)
    }

    @Test
    fun `a new search resets pagination`() = runTest {
        repository.respondWith(listOf(place("a")), page = 0, hasMore = true)
        repository.respondWith(listOf(place("b")), page = 1)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onEvent(SearchEvent.LoadMore)
        advanceUntilIdle()
        repository.requestedFilters.clear()

        viewModel.onEvent(SearchEvent.QueryChanged("osh"))
        advanceUntilIdle()

        assertEquals(0, repository.requestedFilters.single().second)
    }

    @Test
    fun `failed search shows the error`() = runTest {
        repository.failWith(ApiError.Timeout)
        val viewModel = viewModel()

        advanceUntilIdle()

        assertEquals(ScreenState.Error(ApiError.Timeout), viewModel.state.value.results)
    }

    @Test
    fun `cached answer is marked for the screen`() = runTest {
        repository.respondWith(listOf(place("p")), fromCache = true)
        val viewModel = viewModel()

        advanceUntilIdle()

        assertTrue(viewModel.state.value.fromCache)
    }

    @Test
    fun `place tap opens the card`() = runTest {
        repository.respondWith(listOf(place("p-1")))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onEvent(SearchEvent.PlaceClicked("p-1"))

        assertEquals(SearchEffect.OpenPlace("p-1"), viewModel.effects.first())
    }

    @Test
    fun `filter sheet visibility is part of the state`() = runTest {
        repository.respondWith(listOf(place("p")))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onEvent(SearchEvent.FiltersOpened)
        assertTrue(viewModel.state.value.filtersVisible)

        viewModel.onEvent(SearchEvent.FiltersClosed)
        assertFalse(viewModel.state.value.filtersVisible)
    }

    @Test
    fun `default state carries default filters`() {
        assertEquals(DiscoveryFilters(), SearchState().filters)
    }

    private fun viewModel(
        categoryId: String? = null,
        query: String? = null,
        history: FakeSearchHistoryStore = this.history,
    ) = SearchViewModel(
        repository = repository,
        historyStore = history,
        savedStateHandle = SavedStateHandle(
            mapOf("categoryId" to categoryId, "query" to query),
        ),
    )

    private companion object {
        const val DEBOUNCE_MS = 300L
    }
}
