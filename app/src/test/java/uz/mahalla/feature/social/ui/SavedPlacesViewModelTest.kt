package uz.mahalla.feature.social.ui

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
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.discovery.domain.Place
import uz.mahalla.feature.social.data.SavedPlacesPage
import uz.mahalla.feature.social.ui.saved.SavedPlacesEffect
import uz.mahalla.feature.social.ui.saved.SavedPlacesEvent
import uz.mahalla.feature.social.ui.saved.SavedPlacesViewModel
import uz.mahalla.testutil.FakeSocialRepository
import uz.mahalla.testutil.MainDispatcherRule
import uz.mahalla.testutil.place

/** «Избранное» (issue #75): страница, догрузка и перечит на возврате. */
@OptIn(ExperimentalCoroutinesApi::class)
class SavedPlacesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val repository = FakeSocialRepository()

    @Test
    fun `the first page is loaded on open`() = runTest {
        repository.savedPages[0] = success(listOf(place("p-1"), place("p-2")))

        val state = viewModel().state.value

        assertEquals(
            listOf("p-1", "p-2"),
            (state.places as ScreenState.Content).data.map(Place::id),
        )
    }

    @Test
    fun `an empty answer is empty, not a list of nothing`() = runTest {
        repository.savedPages[0] = success(emptyList())

        assertTrue(viewModel().state.value.places is ScreenState.Empty)
    }

    @Test
    fun `a failure offers a retry that works`() = runTest {
        repository.savedPages[0] = ApiResult.Failure(ApiError.NoConnection)
        val viewModel = viewModel()
        assertTrue(viewModel.state.value.places is ScreenState.Error)

        repository.savedPages[0] = success(listOf(place("p-1")))
        viewModel.onEvent(SavedPlacesEvent.Retry)

        assertTrue(viewModel.state.value.places is ScreenState.Content)
    }

    @Test
    fun `the next page is appended without duplicates`() = runTest {
        repository.savedPages[0] = success(listOf(place("p-1"), place("p-2")), hasMore = true)
        repository.savedPages[1] = success(listOf(place("p-2"), place("p-3")))
        val viewModel = viewModel()

        viewModel.onEvent(SavedPlacesEvent.LoadMore)

        assertEquals(
            listOf("p-1", "p-2", "p-3"),
            (viewModel.state.value.places as ScreenState.Content).data.map(Place::id),
        )
        assertFalse(viewModel.state.value.hasMore)
    }

    @Test
    fun `a failed load more keeps the list and explains itself`() = runTest {
        repository.savedPages[0] = success(listOf(place("p-1")), hasMore = true)
        repository.savedPages[1] = ApiResult.Failure(ApiError.Timeout)
        val viewModel = viewModel()

        viewModel.onEvent(SavedPlacesEvent.LoadMore)

        assertTrue(viewModel.state.value.places is ScreenState.Content)
        assertFalse(viewModel.state.value.isLoadingMore)
        assertEquals(ApiError.Timeout, viewModel.state.value.loadMoreFailure?.error)
    }

    @Test
    fun `returning to the screen rereads the list`() = runTest {
        // Место могли убрать из избранного на его карточке, пока экран был в
        // фоне.
        repository.savedPages[0] = success(listOf(place("p-1"), place("p-2")))
        val viewModel = viewModel()

        repository.savedPages[0] = success(listOf(place("p-1")))
        viewModel.onEvent(SavedPlacesEvent.ScreenResumed)

        assertEquals(
            listOf("p-1"),
            (viewModel.state.value.places as ScreenState.Content).data.map(Place::id),
        )
        assertEquals(listOf(0, 0), repository.requestedSavedPages)
    }

    @Test
    fun `pull to refresh starts from the first page again`() = runTest {
        repository.savedPages[0] = success(listOf(place("p-1")), hasMore = true)
        repository.savedPages[1] = success(listOf(place("p-2")))
        val viewModel = viewModel()
        viewModel.onEvent(SavedPlacesEvent.LoadMore)

        repository.savedPages[0] = success(listOf(place("p-9")))
        viewModel.onEvent(SavedPlacesEvent.Refreshed)

        assertEquals(
            listOf("p-9"),
            (viewModel.state.value.places as ScreenState.Content).data.map(Place::id),
        )
        assertFalse(viewModel.state.value.isRefreshing)
    }

    @Test
    fun `opening a place is an effect, not a direct navigation call`() = runTest {
        repository.savedPages[0] = success(listOf(place("p-1")))
        val viewModel = viewModel()

        viewModel.onEvent(SavedPlacesEvent.PlaceClicked("p-1"))

        assertEquals(SavedPlacesEffect.OpenPlace("p-1"), viewModel.effects.first())
    }

    private fun viewModel() = SavedPlacesViewModel(repository)

    private fun success(items: List<Place>, hasMore: Boolean = false) =
        ApiResult.Success(SavedPlacesPage(items = items, hasMore = hasMore))
}
