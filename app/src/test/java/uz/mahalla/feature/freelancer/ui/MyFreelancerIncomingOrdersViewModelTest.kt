package uz.mahalla.feature.freelancer.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.freelancer.domain.FreelancerOrder
import uz.mahalla.feature.freelancer.domain.FreelancerOrderPage
import uz.mahalla.feature.freelancer.domain.FreelancerOrderStatus
import uz.mahalla.feature.freelancer.ui.orders.MyFreelancerIncomingOrdersEvent
import uz.mahalla.feature.freelancer.ui.orders.MyFreelancerIncomingOrdersViewModel
import uz.mahalla.testutil.FakeFreelancerRepository
import uz.mahalla.testutil.MainDispatcherRule

/**
 * Входящие заказы мастера (issue #190): список — тот же пагинационный
 * контракт, что у «Моих заказов у мастеров» ([MyFreelancerOrdersViewModelTest]),
 * плюс смена статуса.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MyFreelancerIncomingOrdersViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeFreelancerRepository()

    @Test
    fun `first page loads on start`() = runTest(mainDispatcherRule.dispatcher) {
        repository.defaultIncomingOrderPage = ApiResult.Success(page(listOf(order("o-1"))))

        val viewModel = MyFreelancerIncomingOrdersViewModel(repository)
        runCurrent()

        assertEquals(listOf(0), repository.requestedIncomingOrderPages)
        val content = viewModel.state.value.orders as ScreenState.Content
        assertEquals(listOf("o-1"), content.data.map { it.id })
    }

    @Test
    fun `empty list is an empty state`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = MyFreelancerIncomingOrdersViewModel(repository)
        runCurrent()

        assertEquals(ScreenState.Empty, viewModel.state.value.orders)
    }

    @Test
    fun `returning to the screen rereads the list`() = runTest(mainDispatcherRule.dispatcher) {
        repository.defaultIncomingOrderPage = ApiResult.Success(page(listOf(order("o-1"))))
        val viewModel = MyFreelancerIncomingOrdersViewModel(repository)
        runCurrent()

        viewModel.onEvent(MyFreelancerIncomingOrdersEvent.ScreenResumed)
        runCurrent()

        assertEquals(listOf(0, 0), repository.requestedIncomingOrderPages)
        assertTrue(viewModel.state.value.orders is ScreenState.Content)
    }

    @Test
    fun `load more appends and deduplicates`() = runTest(mainDispatcherRule.dispatcher) {
        repository.incomingOrderPages[0] =
            ApiResult.Success(page(listOf(order("o-1")), hasMore = true))
        repository.incomingOrderPages[1] =
            ApiResult.Success(page(listOf(order("o-1"), order("o-2"))))
        val viewModel = MyFreelancerIncomingOrdersViewModel(repository)
        runCurrent()

        viewModel.onEvent(MyFreelancerIncomingOrdersEvent.LoadMore)
        runCurrent()

        val content = viewModel.state.value.orders as ScreenState.Content
        assertEquals(listOf("o-1", "o-2"), content.data.map { it.id })
        assertFalse(viewModel.state.value.hasMore)
    }

    @Test
    fun `failed load more keeps the list and shows the reason`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.incomingOrderPages[0] = ApiResult.Success(
                page(listOf(order("o-1")), hasMore = true),
            )
            repository.incomingOrderPages[1] = ApiResult.Failure(ApiError.NoConnection)
            val viewModel = MyFreelancerIncomingOrdersViewModel(repository)
            runCurrent()

            viewModel.onEvent(MyFreelancerIncomingOrdersEvent.LoadMore)
            runCurrent()

            val state = viewModel.state.value
            assertTrue(state.orders is ScreenState.Content)
            assertFalse(state.isLoadingMore)
            assertEquals(ApiError.NoConnection, state.loadMoreFailure?.error)
        }

    @Test
    fun `failed first page becomes an error state`() = runTest(mainDispatcherRule.dispatcher) {
        repository.defaultIncomingOrderPage = ApiResult.Failure(ApiError.Unauthorized)

        val viewModel = MyFreelancerIncomingOrdersViewModel(repository)
        runCurrent()

        assertEquals(
            ApiError.Unauthorized,
            (viewModel.state.value.orders as ScreenState.Error).error,
        )
    }

    @Test
    fun `pull to refresh reloads from the first page`() = runTest(mainDispatcherRule.dispatcher) {
        repository.incomingOrderPages[0] =
            ApiResult.Success(page(listOf(order("o-1")), hasMore = true))
        repository.incomingOrderPages[1] = ApiResult.Success(page(listOf(order("o-2"))))
        val viewModel = MyFreelancerIncomingOrdersViewModel(repository)
        runCurrent()
        viewModel.onEvent(MyFreelancerIncomingOrdersEvent.LoadMore)
        runCurrent()

        viewModel.onEvent(MyFreelancerIncomingOrdersEvent.Refreshed)
        runCurrent()

        assertFalse(viewModel.state.value.isRefreshing)
        val content = viewModel.state.value.orders as ScreenState.Content
        assertEquals(listOf("o-1"), content.data.map { it.id })
        assertEquals(listOf(0, 1, 0), repository.requestedIncomingOrderPages)
    }

    /** Принять — это `updateOrderStatus(orderId, Accepted)`, и список перечитывается. */
    @Test
    fun `accepting an order sends the right status and reloads the list`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.defaultIncomingOrderPage = ApiResult.Success(page(listOf(order("o-1"))))
            val viewModel = MyFreelancerIncomingOrdersViewModel(repository)
            runCurrent()

            viewModel.onEvent(MyFreelancerIncomingOrdersEvent.AcceptClicked("o-1"))
            runCurrent()

            assertEquals(
                listOf("o-1" to FreelancerOrderStatus.Accepted),
                repository.orderStatusChanges,
            )
            // Загрузка после успеха — источник правды сервер, а не оптимистичная правка.
            assertEquals(listOf(0, 0), repository.requestedIncomingOrderPages)
            assertTrue(viewModel.state.value.pendingOrderIds.isEmpty())
        }

    @Test
    fun `rejecting an order sends Rejected`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = MyFreelancerIncomingOrdersViewModel(repository)
        runCurrent()

        viewModel.onEvent(MyFreelancerIncomingOrdersEvent.RejectClicked("o-1"))
        runCurrent()

        assertEquals(listOf("o-1" to FreelancerOrderStatus.Rejected), repository.orderStatusChanges)
    }

    @Test
    fun `completing an order sends Completed`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = MyFreelancerIncomingOrdersViewModel(repository)
        runCurrent()

        viewModel.onEvent(MyFreelancerIncomingOrdersEvent.CompleteClicked("o-1"))
        runCurrent()

        assertEquals(listOf("o-1" to FreelancerOrderStatus.Completed), repository.orderStatusChanges)
    }

    /** Пока летит смена статуса, повторный клик по тому же заказу игнорируется. */
    @Test
    fun `a second click on the same order while pending is ignored`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = MyFreelancerIncomingOrdersViewModel(repository)
            runCurrent()

            viewModel.onEvent(MyFreelancerIncomingOrdersEvent.AcceptClicked("o-1"))
            viewModel.onEvent(MyFreelancerIncomingOrdersEvent.AcceptClicked("o-1"))
            runCurrent()

            assertEquals(1, repository.orderStatusChanges.size)
        }

    /**
     * `pendingOrderIds` — набор, а не одно значение: заказы независимы, и
     * пока один ждёт ответа сервера, клик по **другому** заказу должен
     * пройти, а не молча проигнорироваться (это и отличает набор от
     * единственного `pendingOrderId`, которым экран управлялся раньше).
     */
    @Test
    fun `clicking a different order while one is pending still proceeds`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = MyFreelancerIncomingOrdersViewModel(repository)
            runCurrent()

            viewModel.onEvent(MyFreelancerIncomingOrdersEvent.AcceptClicked("o-1"))
            viewModel.onEvent(MyFreelancerIncomingOrdersEvent.RejectClicked("o-2"))
            runCurrent()

            assertEquals(
                listOf("o-1" to FreelancerOrderStatus.Accepted, "o-2" to FreelancerOrderStatus.Rejected),
                repository.orderStatusChanges,
            )
            assertTrue(viewModel.state.value.pendingOrderIds.isEmpty())
        }

    @Test
    fun `failed status change keeps the list and shows the reason`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.defaultIncomingOrderPage = ApiResult.Success(page(listOf(order("o-1"))))
            repository.updateOrderStatusResult = ApiResult.Failure(ApiError.NoConnection)
            val viewModel = MyFreelancerIncomingOrdersViewModel(repository)
            runCurrent()

            viewModel.onEvent(MyFreelancerIncomingOrdersEvent.AcceptClicked("o-1"))
            runCurrent()

            val state = viewModel.state.value
            assertTrue(state.pendingOrderIds.isEmpty())
            assertEquals(ApiError.NoConnection, state.actionFailure?.error)
            // Список остаётся: провал действия не должен смыть уже загруженный экран.
            assertTrue(state.orders is ScreenState.Content)
        }

    /**
     * Баннер прошлого отказа не должен висеть над уже идущей новой попыткой —
     * очищается сразу с началом следующего действия, до его результата.
     */
    @Test
    fun `a new action clears the previous failure banner`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.updateOrderStatusResult = ApiResult.Failure(ApiError.NoConnection)
            val viewModel = MyFreelancerIncomingOrdersViewModel(repository)
            runCurrent()
            viewModel.onEvent(MyFreelancerIncomingOrdersEvent.AcceptClicked("o-1"))
            runCurrent()
            assertEquals(ApiError.NoConnection, viewModel.state.value.actionFailure?.error)

            repository.updateOrderStatusResult = ApiResult.Success(Unit)
            viewModel.onEvent(MyFreelancerIncomingOrdersEvent.AcceptClicked("o-2"))

            assertNull(viewModel.state.value.actionFailure)
        }

    private fun page(items: List<FreelancerOrder>, hasMore: Boolean = false) =
        FreelancerOrderPage(items = items, hasMore = hasMore)

    private fun order(id: String) =
        FreelancerOrder(id = id, status = FreelancerOrderStatus.Pending)
}
