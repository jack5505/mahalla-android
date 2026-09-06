package uz.mahalla.feature.fashion.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.fashion.domain.FashionOrderPage
import uz.mahalla.feature.fashion.ui.orders.FashionOrdersEvent
import uz.mahalla.feature.fashion.ui.orders.FashionOrdersViewModel
import uz.mahalla.feature.food.domain.CartTotals
import uz.mahalla.feature.food.domain.DeliveryMethod
import uz.mahalla.feature.food.domain.Order
import uz.mahalla.feature.food.domain.OrderStatus
import uz.mahalla.feature.food.domain.PaymentMethod
import uz.mahalla.testutil.FakeFashionOrderRepository
import uz.mahalla.testutil.MainDispatcherRule

/**
 * «Мои заказы одежды» (issue #108): список, догрузка и отмена.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FashionOrdersViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val repository = FakeFashionOrderRepository()

    @Test
    fun `orders are loaded on open`() = runTest {
        repository.defaultPage = ApiResult.Success(FashionOrderPage(listOf(order("o-1"))))

        val state = viewModel().state.value

        assertEquals(listOf(0), repository.requestedPages)
        assertEquals(listOf("o-1"), (state.orders as ScreenState.Content).data.map(Order::id))
    }

    @Test
    fun `an empty answer is an empty state, not an error`() = runTest {
        assertTrue(viewModel().state.value.orders is ScreenState.Empty)
    }

    @Test
    fun `returning to the screen re-reads the list`() = runTest {
        // Статус двигает магазин: показанное час назад «принят» ничего не
        // стоит.
        val viewModel = viewModel()

        viewModel.onEvent(FashionOrdersEvent.ScreenResumed)

        assertEquals(listOf(0, 0), repository.requestedPages)
    }

    @Test
    fun `load more appends the next page and deduplicates it`() = runTest {
        repository.pages[0] = ApiResult.Success(
            FashionOrderPage(listOf(order("o-1"), order("o-2")), hasMore = true),
        )
        repository.pages[1] = ApiResult.Success(
            FashionOrderPage(listOf(order("o-2"), order("o-3"))),
        )
        val viewModel = viewModel()

        viewModel.onEvent(FashionOrdersEvent.LoadMore)

        val orders = (viewModel.state.value.orders as ScreenState.Content).data
        assertEquals(listOf("o-1", "o-2", "o-3"), orders.map(Order::id))
    }

    @Test
    fun `a failed load more keeps the list and offers a retry`() = runTest {
        repository.pages[0] = ApiResult.Success(
            FashionOrderPage(listOf(order("o-1")), hasMore = true),
        )
        repository.pages[1] = ApiResult.Failure(ApiError.Timeout)
        val viewModel = viewModel()

        viewModel.onEvent(FashionOrdersEvent.LoadMore)

        val state = viewModel.state.value
        assertTrue(state.orders is ScreenState.Content)
        assertEquals(ApiError.Timeout, state.loadMoreFailure?.error)
    }

    @Test
    fun `an order the shop is already preparing offers no cancel`() = runTest {
        repository.defaultPage = ApiResult.Success(
            FashionOrderPage(listOf(order("o-1", OrderStatus.Preparing))),
        )
        val viewModel = viewModel()

        viewModel.onEvent(FashionOrdersEvent.CancelRequested("o-1"))

        assertNull(viewModel.state.value.confirmCancel)
    }

    @Test
    fun `cancelling asks first and then re-reads the order`() = runTest {
        repository.defaultPage = ApiResult.Success(FashionOrderPage(listOf(order("o-1"))))
        repository.orderResult = ApiResult.Success(order("o-1", OrderStatus.Cancelled))
        val viewModel = viewModel()

        viewModel.onEvent(FashionOrdersEvent.CancelRequested("o-1"))
        viewModel.onEvent(FashionOrdersEvent.CancelDismissed)
        assertTrue(repository.cancelled.isEmpty())

        viewModel.onEvent(FashionOrdersEvent.CancelRequested("o-1"))
        viewModel.onEvent(FashionOrdersEvent.CancelConfirmed)

        assertEquals(listOf("o-1"), repository.cancelled)
        // Новое состояние перечитывается у сервера: ответ отмены описан
        // перекрытой коллизией схемой.
        assertEquals(listOf("o-1"), repository.requestedOrders)
        val orders = (viewModel.state.value.orders as ScreenState.Content).data
        assertEquals(OrderStatus.Cancelled, orders.single().status)
        assertNull(viewModel.state.value.pendingCancelId)
    }

    @Test
    fun `a cancelled order stays in the list even when the re-read fails`() = runTest {
        // Сервер отмену уже подтвердил: оставить строку в прежнем виде значило
        // бы предложить отменить её второй раз.
        repository.defaultPage = ApiResult.Success(FashionOrderPage(listOf(order("o-1"))))
        repository.orderResult = ApiResult.Failure(ApiError.Timeout)
        val viewModel = viewModel()

        viewModel.onEvent(FashionOrdersEvent.CancelRequested("o-1"))
        viewModel.onEvent(FashionOrdersEvent.CancelConfirmed)

        val orders = (viewModel.state.value.orders as ScreenState.Content).data
        assertEquals(OrderStatus.Cancelled, orders.single().status)
    }

    @Test
    fun `a refused cancel keeps the order and shows the server text`() = runTest {
        repository.defaultPage = ApiResult.Success(FashionOrderPage(listOf(order("o-1"))))
        repository.cancelResult = ApiResult.Failure(ApiError.Business("ORDER_ALREADY_SHIPPED"))
        val viewModel = viewModel()

        viewModel.onEvent(FashionOrdersEvent.CancelRequested("o-1"))
        viewModel.onEvent(FashionOrdersEvent.CancelConfirmed)

        val state = viewModel.state.value
        assertEquals(ApiError.Business("ORDER_ALREADY_SHIPPED"), state.cancelFailure?.error)
        assertEquals(
            OrderStatus.Created,
            (state.orders as ScreenState.Content).data.single().status,
        )
        assertNull(state.pendingCancelId)
    }

    private fun viewModel() = FashionOrdersViewModel(repository = repository)

    private fun order(id: String, status: OrderStatus = OrderStatus.Created) = Order(
        id = id,
        placeId = "s-1",
        placeName = "",
        number = "CL-42",
        status = status,
        method = DeliveryMethod.Delivery,
        payment = PaymentMethod.Wallet,
        totals = CartTotals(subtotalSum = 480_000),
        createdAt = null,
    )
}
