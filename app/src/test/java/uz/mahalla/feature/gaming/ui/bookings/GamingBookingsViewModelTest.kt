package uz.mahalla.feature.gaming.ui.bookings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.gaming.domain.GamingBookingPage
import uz.mahalla.testutil.FakeGamingRepository
import uz.mahalla.testutil.MainDispatcherRule
import uz.mahalla.testutil.gamingBooking

/**
 * «Мои брони» (issue #98): страницы, перечит на возврате и провал догрузки.
 *
 * Отмены здесь нет по контракту бэкенда, поэтому и тестировать её нечего —
 * см. `GamingApi`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GamingBookingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeGamingRepository()

    @Test
    fun `the first page is loaded on open`() = runTest(mainDispatcherRule.dispatcher) {
        repository.pages[0] = page(listOf("b-1", "b-2"), hasMore = false)

        val viewModel = viewModel()
        runCurrent()

        assertEquals(
            listOf("b-1", "b-2"),
            (viewModel.state.value.bookings as ScreenState.Content).data.map { it.id },
        )
        assertFalse(viewModel.state.value.hasMore)
    }

    @Test
    fun `no bookings is empty, not an error`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        runCurrent()

        assertEquals(ScreenState.Empty, viewModel.state.value.bookings)
    }

    @Test
    fun `a refusal shows the reason and retry loads again`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.pages[0] = ApiResult.Failure(ApiError.Unauthorized)

            val viewModel = viewModel()
            runCurrent()
            assertEquals(
                ApiError.Unauthorized,
                (viewModel.state.value.bookings as ScreenState.Error).failure.error,
            )

            repository.pages[0] = page(listOf("b-1"), hasMore = false)
            viewModel.onEvent(GamingBookingsEvent.Retry)
            runCurrent()

            assertTrue(viewModel.state.value.bookings is ScreenState.Content)
        }

    @Test
    fun `the next page is appended and duplicates are dropped`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.pages[0] = page(listOf("b-1", "b-2"), hasMore = true)
            // Бронь может приехать на двух соседних страницах, если список
            // изменился между запросами: в `LazyColumn` это дубликат ключа.
            repository.pages[1] = page(listOf("b-2", "b-3"), hasMore = false)

            val viewModel = viewModel()
            runCurrent()
            viewModel.onEvent(GamingBookingsEvent.LoadMore)
            runCurrent()

            assertEquals(
                listOf("b-1", "b-2", "b-3"),
                (viewModel.state.value.bookings as ScreenState.Content).data.map { it.id },
            )
            assertFalse(viewModel.state.value.hasMore)
            assertEquals(listOf(0, 1), repository.requestedPages)
        }

    @Test
    fun `a failed load more keeps the list and shows the reason`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.pages[0] = page(listOf("b-1"), hasMore = true)
            repository.pages[1] = ApiResult.Failure(ApiError.NoConnection)

            val viewModel = viewModel()
            runCurrent()
            viewModel.onEvent(GamingBookingsEvent.LoadMore)
            runCurrent()

            val state = viewModel.state.value
            assertNotNull(state.loadMoreFailure)
            assertFalse(state.isLoadingMore)
            // Уже показанные брони не стираются: догрузка провалилась, а не
            // список.
            assertEquals(1, (state.bookings as ScreenState.Content).data.size)
        }

    @Test
    fun `returning to the screen rereads the list from the first page`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.pages[0] = page(listOf("b-1"), hasMore = false)
            val viewModel = viewModel()
            runCurrent()

            // Состояние брони меняет заведение, а не приложение.
            repository.pages[0] = page(listOf("b-9"), hasMore = false)
            viewModel.onEvent(GamingBookingsEvent.ScreenResumed)
            runCurrent()

            assertEquals(
                listOf("b-9"),
                (viewModel.state.value.bookings as ScreenState.Content).data.map { it.id },
            )
        }

    @Test
    fun `pull to refresh drops the refreshing flag when it is done`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.pages[0] = page(listOf("b-1"), hasMore = false)
            val viewModel = viewModel()
            runCurrent()

            viewModel.onEvent(GamingBookingsEvent.Refreshed)
            runCurrent()

            assertFalse(viewModel.state.value.isRefreshing)
        }

    private fun page(ids: List<String>, hasMore: Boolean) = ApiResult.Success(
        GamingBookingPage(items = ids.map { gamingBooking(id = it) }, hasMore = hasMore),
    )

    private fun viewModel() = GamingBookingsViewModel(repository = repository)
}
