package uz.mahalla.feature.discovery.ui.home

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.discovery.domain.Place
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.testutil.FakeCatalogRepository
import uz.mahalla.testutil.MainDispatcherRule
import uz.mahalla.testutil.place

/** Главная (эпик 4.1): состояния, блоки и переходы. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryHomeViewModelTest {

    // Здесь таймеров нет — загрузка должна выполниться на месте, без
    // advanceUntilIdle() после каждого события.
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val repository = FakeCatalogRepository()

    @Test
    fun `successful load splits the answer into sections`() = runTest {
        repository.respondWith(
            listOf(
                place("near", distanceMeters = 100, rating = 4.0, reviewCount = 5),
                place("top", distanceMeters = 900, rating = 4.9, reviewCount = 200),
            ),
        )

        val state = viewModel().state.value

        val content = (state.content as ScreenState.Content).data
        assertEquals(listOf("near", "top"), content.nearby.map(Place::id))
        assertEquals(listOf("top"), content.recommended.map(Place::id))
        assertFalse(state.isRefreshing)
    }

    @Test
    fun `home asks the catalog without filters`() = runTest {
        // Главная — витрина всего, что рядом; фильтры живут на экране поиска.
        repository.respondWith(listOf(place("p")))

        viewModel()

        val (filters, page) = repository.requestedFilters.single()
        assertTrue(filters.isDefault)
        assertEquals(0, page)
    }

    @Test
    fun `empty answer is an empty state, not empty content`() = runTest {
        repository.respondWith(emptyList())

        assertEquals(ScreenState.Empty, viewModel().state.value.content)
    }

    @Test
    fun `network error becomes an error state`() = runTest {
        repository.failWith(ApiError.NoConnection)

        assertEquals(
            ScreenState.Error(ApiError.NoConnection),
            viewModel().state.value.content,
        )
    }

    @Test
    fun `retry reloads after a failure`() = runTest {
        repository.failWith(ApiError.Timeout)
        val viewModel = viewModel()
        repository.respondWith(listOf(place("p")))

        viewModel.onEvent(DiscoveryHomeEvent.Retry)

        assertTrue(viewModel.state.value.content is ScreenState.Content)
    }

    @Test
    fun `refresh keeps the list on screen instead of showing a skeleton`() = runTest {
        // Обновление поверх готовых данных — не повторная загрузка экрана.
        repository.respondWith(listOf(place("p")))
        val viewModel = viewModel()

        viewModel.onEvent(DiscoveryHomeEvent.Refresh)

        assertTrue(viewModel.state.value.content is ScreenState.Content)
        assertFalse("флаг обновления снимается по завершении", viewModel.state.value.isRefreshing)
    }

    @Test
    fun `cached answer is marked for the screen`() = runTest {
        repository.respondWith(listOf(place("p")), fromCache = true)

        val content = (viewModel().state.value.content as ScreenState.Content).data

        assertTrue(content.fromCache)
    }

    @Test
    fun `category tap opens search with that category`() = runTest {
        repository.respondWith(listOf(place("p")))
        val viewModel = viewModel()

        viewModel.onEvent(DiscoveryHomeEvent.CategoryClicked(PlaceCategory.Pharmacy))

        assertEquals(
            DiscoveryHomeEffect.OpenSearch(PlaceCategory.Pharmacy),
            viewModel.effects.first(),
        )
    }

    @Test
    fun `search bar opens search without a category`() = runTest {
        repository.respondWith(listOf(place("p")))
        val viewModel = viewModel()

        viewModel.onEvent(DiscoveryHomeEvent.SearchClicked)

        assertEquals(DiscoveryHomeEffect.OpenSearch(null), viewModel.effects.first())
    }

    @Test
    fun `place tap opens the card`() = runTest {
        repository.respondWith(listOf(place("p-1")))
        val viewModel = viewModel()

        viewModel.onEvent(DiscoveryHomeEvent.PlaceClicked("p-1"))

        assertEquals(DiscoveryHomeEffect.OpenPlace("p-1"), viewModel.effects.first())
    }

    @Test
    fun `map button opens the map`() = runTest {
        repository.respondWith(listOf(place("p")))
        val viewModel = viewModel()

        viewModel.onEvent(DiscoveryHomeEvent.MapClicked)

        assertEquals(DiscoveryHomeEffect.OpenMap, viewModel.effects.first())
    }

    @Test
    fun `all six categories are offered`() = runTest {
        repository.respondWith(listOf(place("p")))

        assertEquals(PlaceCategory.selectable, viewModel().state.value.categories)
    }

    private fun viewModel() = DiscoveryHomeViewModel(repository)
}
