package uz.mahalla.feature.food.ui.order

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.food.domain.DeliveryMethod
import uz.mahalla.feature.food.domain.OrderStatus
import uz.mahalla.testutil.FakeOrderRepository
import uz.mahalla.testutil.MainDispatcherRule
import uz.mahalla.testutil.order

/**
 * Статус заказа (эпик 5.4). Опрос идёт по виртуальному времени
 * (`StandardTestDispatcher`), поэтому тест не ждёт реальных секунд.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class OrderStatusViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeOrderRepository()

    @Test
    fun `the order is loaded for the id from the route`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        runCurrent()

        assertEquals(
            OrderStatus.Created,
            (viewModel.state.value.order as ScreenState.Content).data.status,
        )
        viewModel.stopPolling()
    }

    @Test
    fun `stages follow the delivery method`() = runTest(mainDispatcherRule.dispatcher) {
        repository.loaded = ApiResult.Success(order(method = DeliveryMethod.Pickup))
        val viewModel = viewModel()
        runCurrent()

        assertTrue(viewModel.state.value.stages.contains(OrderStatus.ReadyForPickup))
        assertFalse(viewModel.state.value.stages.contains(OrderStatus.Delivering))
        viewModel.stopPolling()
    }

    @Test
    fun `the status is polled until it becomes final`() = runTest(mainDispatcherRule.dispatcher) {
        repository.pollResponses += ApiResult.Success(order(status = OrderStatus.Created))
        repository.pollResponses += ApiResult.Success(order(status = OrderStatus.Preparing))
        repository.pollResponses += ApiResult.Success(order(status = OrderStatus.Completed))
        val viewModel = viewModel()
        runCurrent()

        advanceTimeBy(POLL_MS)
        runCurrent()
        assertEquals(OrderStatus.Preparing, viewModel.state.value.data?.status)

        advanceTimeBy(POLL_MS)
        runCurrent()
        assertEquals(OrderStatus.Completed, viewModel.state.value.data?.status)

        // Завершённый заказ больше не опрашивается: вечный поллинг — это
        // разряженная батарея за ничто.
        val requests = repository.loadCount
        advanceTimeBy(POLL_MS * 3)
        runCurrent()
        assertEquals(requests, repository.loadCount)
    }

    @Test
    fun `a polling failure does not wipe the order already shown`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.pollResponses += ApiResult.Success(order(status = OrderStatus.Preparing))
            repository.pollResponses += ApiResult.Failure(ApiError.NoConnection)
            repository.pollResponses += ApiResult.Success(order(status = OrderStatus.Delivering))
            val viewModel = viewModel()
            runCurrent()

            advanceTimeBy(POLL_MS)
            runCurrent()
            assertEquals(OrderStatus.Preparing, viewModel.state.value.data?.status)

            advanceTimeBy(POLL_MS)
            runCurrent()
            assertEquals(OrderStatus.Delivering, viewModel.state.value.data?.status)
            viewModel.stopPolling()
        }

    @Test
    fun `polling stops in the background and resumes when the screen returns`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel()
            runCurrent()
            val loadedWhileVisible = repository.loadCount

            viewModel.onEvent(OrderStatusEvent.ScreenStopped)
            advanceTimeBy(POLL_MS * 3)
            runCurrent()
            assertEquals(loadedWhileVisible, repository.loadCount)

            viewModel.onEvent(OrderStatusEvent.ScreenStarted)
            runCurrent()
            assertEquals(loadedWhileVisible + 1, repository.loadCount)
            viewModel.stopPolling()
        }

    @Test
    fun `a finished order is not polled again after returning to the screen`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.loaded = ApiResult.Success(order(status = OrderStatus.Completed))
            val viewModel = viewModel()
            runCurrent()
            val loaded = repository.loadCount

            viewModel.onEvent(OrderStatusEvent.ScreenStopped)
            viewModel.onEvent(OrderStatusEvent.ScreenStarted)
            runCurrent()

            assertEquals(loaded, repository.loadCount)
        }

    @Test
    fun `a failure on the first load is a normal error with a retry`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.loaded = ApiResult.Failure(ApiError.NoConnection)
            val viewModel = viewModel()
            runCurrent()

            assertEquals(ScreenState.Error(ApiError.NoConnection), viewModel.state.value.order)

            repository.loaded = ApiResult.Success(order())
            viewModel.onEvent(OrderStatusEvent.Retry)
            runCurrent()

            assertTrue(viewModel.state.value.order is ScreenState.Content)
            viewModel.stopPolling()
        }

    @Test
    fun `cancelling is confirmed first and then applied`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel()
            runCurrent()

            viewModel.onEvent(OrderStatusEvent.CancelClicked)
            assertTrue(viewModel.state.value.cancelConfirmVisible)

            viewModel.onEvent(OrderStatusEvent.CancelConfirmed)
            runCurrent()

            assertEquals(OrderStatus.Cancelled, viewModel.state.value.data?.status)
            assertFalse(viewModel.state.value.isCancelling)
        }

    @Test
    fun `a cooking order offers no cancel button`() = runTest(mainDispatcherRule.dispatcher) {
        repository.loaded = ApiResult.Success(order(status = OrderStatus.Preparing))
        val viewModel = viewModel()
        runCurrent()

        assertFalse(viewModel.state.value.canCancel)
        viewModel.stopPolling()
    }

    @Test
    fun `a failed cancel says so and keeps the order`() = runTest(mainDispatcherRule.dispatcher) {
        repository.cancelled = ApiResult.Failure(ApiError.Http(409, null))
        val viewModel = viewModel()
        runCurrent()

        viewModel.onEvent(OrderStatusEvent.CancelConfirmed)
        runCurrent()

        assertTrue(viewModel.state.value.cancelFailed)
        assertEquals(OrderStatus.Created, viewModel.state.value.data?.status)
        viewModel.stopPolling()
    }

    @Test
    fun `repeating refills the cart and opens it`() = runTest(mainDispatcherRule.dispatcher) {
        repository.loaded = ApiResult.Success(order(status = OrderStatus.Completed))
        val viewModel = viewModel()
        runCurrent()

        viewModel.onEvent(OrderStatusEvent.RepeatClicked)
        runCurrent()

        assertEquals("o-1", repository.repeatedOrderId)
        assertEquals(OrderStatusEffect.OpenCart("place-1"), viewModel.effects.first())
    }

    @Test
    fun `an unfinished order cannot be repeated`() = runTest(mainDispatcherRule.dispatcher) {
        repository.loaded = ApiResult.Success(order(status = OrderStatus.Preparing))
        val viewModel = viewModel()
        runCurrent()

        viewModel.onEvent(OrderStatusEvent.RepeatClicked)
        runCurrent()

        assertFalse(viewModel.state.value.canRepeat)
        assertEquals(null, repository.repeatedOrderId)
        viewModel.stopPolling()
    }

    private fun viewModel() = OrderStatusViewModel(
        repository = repository,
        savedStateHandle = SavedStateHandle(mapOf("orderId" to "o-1")),
    )

    /**
     * Опрос идёт, пока заказ не финальный, а `runTest` в конце прокручивает
     * виртуальное время до простоя — то есть висел бы вечно. На устройстве
     * опрос останавливает уход экрана в фон; тест делает то же самое тем же
     * событием.
     */
    private fun OrderStatusViewModel.stopPolling() {
        onEvent(OrderStatusEvent.ScreenStopped)
    }

    private companion object {
        const val POLL_MS = 5_000L
    }
}
