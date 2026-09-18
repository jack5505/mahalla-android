package uz.mahalla.feature.business.ui.orders

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.business.domain.BusinessOrder
import uz.mahalla.feature.business.domain.BusinessOrderFilter
import uz.mahalla.feature.business.domain.BusinessOrderPage
import uz.mahalla.feature.food.domain.DeliveryMethod
import uz.mahalla.feature.food.domain.OrderStatus
import uz.mahalla.feature.food.domain.PaymentMethod
import uz.mahalla.navigation.BusinessArgs
import uz.mahalla.testutil.FakeBusinessRepository
import uz.mahalla.testutil.MainDispatcherRule

/**
 * Входящие заказы (задача 12.3).
 *
 * Главное — что переход статуса проверяется **до** запроса: экран рисует
 * только разрешённые кнопки, но событие может прийти на устаревший список, и
 * тогда отказ сервера показался бы там, где приложение всё знало само.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BusinessOrdersViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun `the first page is loaded without a status filter`() = runTest {
        val repository = FakeBusinessRepository()
        repository.defaultOrderPage = page(listOf(order("o-1", OrderStatus.Created)))

        val state = viewModel(repository).state.value

        assertEquals(listOf(null to 0), repository.orderRequests)
        assertEquals(1, (state.orders as ScreenState.Content).data.size)
    }

    @Test
    fun `an empty answer is an empty state, not an error`() = runTest {
        val state = viewModel(FakeBusinessRepository()).state.value

        assertTrue(state.orders is ScreenState.Empty)
        assertFalse(state.hasMore)
    }

    /**
     * Смена вкладки — новый запрос, а не локальная фильтрация: ручка
     * пагинирована, и нужные заказы могут лежать на второй странице.
     */
    @Test
    fun `changing the tab asks the server with a status`() = runTest {
        val repository = FakeBusinessRepository()
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessOrdersEvent.FilterSelected(BusinessOrderFilter.Ready))

        assertEquals(listOf(null to 0, "READY" to 0), repository.orderRequests)
        assertEquals(BusinessOrderFilter.Ready, viewModel.state.value.filter)
    }

    @Test
    fun `selecting the current tab does not repeat the request`() = runTest {
        val repository = FakeBusinessRepository()
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessOrdersEvent.FilterSelected(BusinessOrderFilter.All))

        assertEquals(listOf(null to 0), repository.orderRequests)
    }

    @Test
    fun `accepting a new order moves it to accepted`() = runTest {
        val repository = FakeBusinessRepository()
        repository.defaultOrderPage = page(listOf(order("o-1", OrderStatus.Created)))
        repository.updateOrderResult =
            ApiResult.Success(order("o-1", OrderStatus.Confirmed))
        val viewModel = viewModel(repository)

        viewModel.onEvent(
            BusinessOrdersEvent.StatusSelected("o-1", OrderStatus.Confirmed),
        )

        assertEquals(listOf("o-1" to OrderStatus.Confirmed), repository.statusUpdates)
        val orders = (viewModel.state.value.orders as ScreenState.Content).data
        assertEquals(OrderStatus.Confirmed, orders.single().status)
        assertNull(viewModel.state.value.pendingOrderId)
    }

    @Test
    fun `a forbidden transition does not reach the server`() = runTest {
        val repository = FakeBusinessRepository()
        repository.defaultOrderPage = page(listOf(order("o-1", OrderStatus.Created)))
        val viewModel = viewModel(repository)

        // Через шаг: `NEW` → `READY` бэкенд не разрешал бы, и спрашивать
        // его об этом незачем.
        viewModel.onEvent(
            BusinessOrdersEvent.StatusSelected("o-1", OrderStatus.ReadyForPickup),
        )

        assertTrue(repository.statusUpdates.isEmpty())
    }

    @Test
    fun `a delivery cannot be handed over before the courier takes it`() = runTest {
        val repository = FakeBusinessRepository()
        repository.defaultOrderPage = page(
            listOf(
                order("o-1", OrderStatus.ReadyForPickup, method = DeliveryMethod.Delivery),
            ),
        )
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessOrdersEvent.StatusSelected("o-1", OrderStatus.Completed))

        assertTrue(repository.statusUpdates.isEmpty())
    }

    @Test
    fun `a ready pickup is handed over at once`() = runTest {
        val repository = FakeBusinessRepository()
        repository.defaultOrderPage = page(
            listOf(order("o-1", OrderStatus.ReadyForPickup, method = DeliveryMethod.Pickup)),
        )
        repository.updateOrderResult = ApiResult.Success(order("o-1", OrderStatus.Completed))
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessOrdersEvent.StatusSelected("o-1", OrderStatus.Completed))

        assertEquals(listOf("o-1" to OrderStatus.Completed), repository.statusUpdates)
    }

    @Test
    fun `an action on an unknown order does not reach the server`() = runTest {
        val repository = FakeBusinessRepository()
        repository.defaultOrderPage = page(listOf(order("o-1", OrderStatus.Created)))
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessOrdersEvent.StatusSelected("o-404", OrderStatus.Confirmed))

        assertTrue(repository.statusUpdates.isEmpty())
    }

    @Test
    fun `a refusal keeps the list and shows the server message`() = runTest {
        val repository = FakeBusinessRepository()
        repository.defaultOrderPage = page(listOf(order("o-1", OrderStatus.Created)))
        repository.updateOrderResult = ApiResult.Failure(ApiFailure(ApiError.Forbidden))
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessOrdersEvent.StatusSelected("o-1", OrderStatus.Confirmed))

        val state = viewModel.state.value
        assertEquals(ApiError.Forbidden, state.actionFailure?.error)
        assertEquals(
            OrderStatus.Created,
            (state.orders as ScreenState.Content).data.single().status,
        )
        assertNull(state.pendingOrderId)
    }

    /**
     * Заказ остаётся в списке даже когда выпадает из фильтра: строка,
     * исчезнувшая ровно в момент нажатия, читается как «нажал не туда».
     */
    @Test
    fun `an order stays in the list after leaving the filter`() = runTest {
        val repository = FakeBusinessRepository()
        repository.orderPages["NEW" to 0] = page(listOf(order("o-1", OrderStatus.Created)))
        repository.updateOrderResult = ApiResult.Success(order("o-1", OrderStatus.Confirmed))
        val viewModel = viewModel(repository)
        viewModel.onEvent(BusinessOrdersEvent.FilterSelected(BusinessOrderFilter.New))

        viewModel.onEvent(BusinessOrdersEvent.StatusSelected("o-1", OrderStatus.Confirmed))

        val orders = (viewModel.state.value.orders as ScreenState.Content).data
        assertEquals(1, orders.size)
        assertEquals(OrderStatus.Confirmed, orders.single().status)
    }

    @Test
    fun `the next page is appended, not replaced`() = runTest {
        val repository = FakeBusinessRepository()
        repository.orderPages[null to 0] =
            page(listOf(order("o-1", OrderStatus.Created)), hasMore = true)
        repository.orderPages[null to 1] = page(listOf(order("o-2", OrderStatus.Preparing)))
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessOrdersEvent.LoadMore)

        val orders = (viewModel.state.value.orders as ScreenState.Content).data
        assertEquals(listOf("o-1", "o-2"), orders.map(BusinessOrder::id))
        assertFalse(viewModel.state.value.hasMore)
    }

    /** Один и тот же заказ на двух страницах — дубликат ключа и падение списка. */
    @Test
    fun `a duplicate order is dropped when appending`() = runTest {
        val repository = FakeBusinessRepository()
        repository.orderPages[null to 0] =
            page(listOf(order("o-1", OrderStatus.Created)), hasMore = true)
        repository.orderPages[null to 1] = page(
            listOf(order("o-1", OrderStatus.Created), order("o-2", OrderStatus.Created)),
        )
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessOrdersEvent.LoadMore)

        val orders = (viewModel.state.value.orders as ScreenState.Content).data
        assertEquals(listOf("o-1", "o-2"), orders.map(BusinessOrder::id))
    }

    @Test
    fun `a failed load-more keeps the orders and remembers the reason`() = runTest {
        val repository = FakeBusinessRepository()
        repository.orderPages[null to 0] =
            page(listOf(order("o-1", OrderStatus.Created)), hasMore = true)
        repository.orderPages[null to 1] = ApiResult.Failure(ApiFailure(ApiError.Timeout))
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessOrdersEvent.LoadMore)

        val state = viewModel.state.value
        assertEquals(1, (state.orders as ScreenState.Content).data.size)
        assertEquals(ApiError.Timeout, state.loadMoreFailure?.error)
        assertFalse(state.isLoadingMore)
    }

    @Test
    fun `returning to the screen re-reads the first page`() = runTest {
        val repository = FakeBusinessRepository()
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessOrdersEvent.ScreenResumed)

        assertEquals(listOf(null to 0, null to 0), repository.orderRequests)
    }

    @Test
    fun `the new count counts only the orders waiting for an answer`() = runTest {
        val repository = FakeBusinessRepository()
        repository.defaultOrderPage = page(
            listOf(
                order("o-1", OrderStatus.Created),
                order("o-2", OrderStatus.Created),
                order("o-3", OrderStatus.Preparing),
            ),
        )

        assertEquals(2, viewModel(repository).state.value.newCount)
    }

    private fun viewModel(repository: FakeBusinessRepository) = BusinessOrdersViewModel(
        repository = repository,
        savedStateHandle = SavedStateHandle(
            mapOf(
                BusinessArgs.PLACE_ID to FakeBusinessRepository.PLACE_ID,
                BusinessArgs.PLACE_NAME to "Osh Markazi",
            ),
        ),
    )

    private fun page(items: List<BusinessOrder>, hasMore: Boolean = false) =
        ApiResult.Success(BusinessOrderPage(items = items, hasMore = hasMore))

    private fun order(
        id: String,
        status: OrderStatus,
        method: DeliveryMethod = DeliveryMethod.Pickup,
    ) = BusinessOrder(
        id = id,
        number = "F-$id",
        status = status,
        method = method,
        payment = PaymentMethod.Cash,
        totalSum = 32_000,
    )
}
