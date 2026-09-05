package uz.mahalla.feature.freelancer.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.freelancer.domain.FreelancerOrder
import uz.mahalla.feature.freelancer.domain.FreelancerOrderPage
import uz.mahalla.feature.freelancer.domain.FreelancerOrderStatus
import uz.mahalla.feature.freelancer.ui.orders.MyFreelancerOrdersEvent
import uz.mahalla.feature.freelancer.ui.orders.MyFreelancerOrdersViewModel
import uz.mahalla.testutil.FakeFreelancerRepository
import uz.mahalla.testutil.MainDispatcherRule

/** «Мои заказы у мастеров» (issue #107): список, догрузка, перечит. */
@OptIn(ExperimentalCoroutinesApi::class)
class MyFreelancerOrdersViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeFreelancerRepository()

    @Test
    fun `first page loads on start`() = runTest(mainDispatcherRule.dispatcher) {
        repository.defaultMyOrderPage = ApiResult.Success(page(listOf(order("o-1"))))

        val viewModel = MyFreelancerOrdersViewModel(repository)
        runCurrent()

        assertEquals(listOf(0), repository.requestedMyOrderPages)
        val content = viewModel.state.value.orders as ScreenState.Content
        assertEquals(listOf("o-1"), content.data.map { it.id })
    }

    @Test
    fun `empty list is an empty state`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = MyFreelancerOrdersViewModel(repository)
        runCurrent()

        assertEquals(ScreenState.Empty, viewModel.state.value.orders)
    }

    /**
     * Статус меняет мастер из своего кабинета, пока приложение в фоне: список,
     * показанный час назад, ничего не стоит.
     */
    @Test
    fun `returning to the screen rereads the list`() = runTest(mainDispatcherRule.dispatcher) {
        repository.defaultMyOrderPage = ApiResult.Success(page(listOf(order("o-1"))))
        val viewModel = MyFreelancerOrdersViewModel(repository)
        runCurrent()

        viewModel.onEvent(MyFreelancerOrdersEvent.ScreenResumed)
        runCurrent()

        assertEquals(listOf(0, 0), repository.requestedMyOrderPages)
        // Скелетона при этом нет: список уже на экране.
        assertTrue(viewModel.state.value.orders is ScreenState.Content)
    }

    /** Пока идёт загрузка, перезапрашивать нечего. */
    @Test
    fun `resume during the first load does not double the request`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = MyFreelancerOrdersViewModel(repository)

            viewModel.onEvent(MyFreelancerOrdersEvent.ScreenResumed)
            runCurrent()

            assertEquals(listOf(0), repository.requestedMyOrderPages)
        }

    @Test
    fun `load more appends and deduplicates`() = runTest(mainDispatcherRule.dispatcher) {
        repository.myOrderPages[0] = ApiResult.Success(page(listOf(order("o-1")), hasMore = true))
        repository.myOrderPages[1] = ApiResult.Success(page(listOf(order("o-1"), order("o-2"))))
        val viewModel = MyFreelancerOrdersViewModel(repository)
        runCurrent()

        viewModel.onEvent(MyFreelancerOrdersEvent.LoadMore)
        runCurrent()

        val content = viewModel.state.value.orders as ScreenState.Content
        assertEquals(listOf("o-1", "o-2"), content.data.map { it.id })
        assertFalse(viewModel.state.value.hasMore)
    }

    @Test
    fun `failed load more keeps the list and shows the reason`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.myOrderPages[0] = ApiResult.Success(
                page(listOf(order("o-1")), hasMore = true),
            )
            repository.myOrderPages[1] = ApiResult.Failure(ApiError.NoConnection)
            val viewModel = MyFreelancerOrdersViewModel(repository)
            runCurrent()

            viewModel.onEvent(MyFreelancerOrdersEvent.LoadMore)
            runCurrent()

            val state = viewModel.state.value
            assertTrue(state.orders is ScreenState.Content)
            assertFalse(state.isLoadingMore)
            assertEquals(ApiError.NoConnection, state.loadMoreFailure?.error)
        }

    @Test
    fun `failed first page becomes an error state`() = runTest(mainDispatcherRule.dispatcher) {
        repository.defaultMyOrderPage = ApiResult.Failure(ApiError.Unauthorized)

        val viewModel = MyFreelancerOrdersViewModel(repository)
        runCurrent()

        assertEquals(
            ApiError.Unauthorized,
            (viewModel.state.value.orders as ScreenState.Error).error,
        )
    }

    @Test
    fun `pull to refresh reloads from the first page`() = runTest(mainDispatcherRule.dispatcher) {
        repository.myOrderPages[0] = ApiResult.Success(page(listOf(order("o-1")), hasMore = true))
        repository.myOrderPages[1] = ApiResult.Success(page(listOf(order("o-2"))))
        val viewModel = MyFreelancerOrdersViewModel(repository)
        runCurrent()
        viewModel.onEvent(MyFreelancerOrdersEvent.LoadMore)
        runCurrent()

        viewModel.onEvent(MyFreelancerOrdersEvent.Refreshed)
        runCurrent()

        assertFalse(viewModel.state.value.isRefreshing)
        val content = viewModel.state.value.orders as ScreenState.Content
        assertEquals(listOf("o-1"), content.data.map { it.id })
        assertEquals(listOf(0, 1, 0), repository.requestedMyOrderPages)
    }

    private fun page(items: List<FreelancerOrder>, hasMore: Boolean = false) =
        FreelancerOrderPage(items = items, hasMore = hasMore)

    private fun order(id: String) =
        FreelancerOrder(id = id, status = FreelancerOrderStatus.Pending)
}
