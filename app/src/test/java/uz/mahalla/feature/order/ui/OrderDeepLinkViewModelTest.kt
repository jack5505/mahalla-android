package uz.mahalla.feature.order.ui

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.activity.domain.ActivityKind
import uz.mahalla.testutil.FakeOrderVerticalResolver
import uz.mahalla.testutil.MainDispatcherRule

/**
 * Резолвер вертикали заказа для deep link'а (issue #343): экран не должен
 * молча уводить одежду и аптеку на статус заказа еды — то, из-за чего
 * заведён #343.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class OrderDeepLinkViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun `a food order resolves to the food status screen`() = runTest {
        val resolver = FakeOrderVerticalResolver(ApiResult.Success(ActivityKind.FoodOrder))

        val state = viewModel(resolver, orderId = "o-1").state.value

        assertEquals(
            ScreenState.Content(OrderDeepLinkDestination.Food("o-1")),
            state,
        )
        assertEquals("o-1", resolver.lastOrderId)
    }

    @Test
    fun `a clothing order resolves to the clothing orders list, not the food screen`() = runTest {
        val resolver = FakeOrderVerticalResolver(ApiResult.Success(ActivityKind.ClothingOrder))

        val state = viewModel(resolver, orderId = "o-2").state.value

        assertEquals(ScreenState.Content(OrderDeepLinkDestination.Clothing), state)
    }

    @Test
    fun `a pharmacy order resolves to activities, not the food screen`() = runTest {
        val resolver = FakeOrderVerticalResolver(ApiResult.Success(ActivityKind.PharmacyOrder))

        val state = viewModel(resolver, orderId = "o-3").state.value

        assertEquals(ScreenState.Content(OrderDeepLinkDestination.Activities), state)
    }

    @Test
    fun `a vertical the client does not know yet falls back to activities`() = runTest {
        val resolver = FakeOrderVerticalResolver(ApiResult.Success(ActivityKind.OtherOrder))

        val state = viewModel(resolver, orderId = "o-4").state.value

        assertEquals(ScreenState.Content(OrderDeepLinkDestination.Activities), state)
    }

    @Test
    fun `a network failure is shown as an error, not silently redirected`() = runTest {
        val resolver = FakeOrderVerticalResolver(ApiResult.Failure(ApiError.NoConnection))

        val state = viewModel(resolver, orderId = "o-5").state.value

        assertEquals(ScreenState.Error(ApiFailure(ApiError.NoConnection)), state)
    }

    @Test
    fun `retry asks the resolver again`() = runTest {
        val resolver = FakeOrderVerticalResolver(ApiResult.Failure(ApiError.NoConnection))
        val viewModel = viewModel(resolver, orderId = "o-6")
        assertEquals(1, resolver.callCount)

        resolver.result = ApiResult.Success(ActivityKind.FoodOrder)
        viewModel.retry()

        assertEquals(2, resolver.callCount)
        assertEquals(ScreenState.Content(OrderDeepLinkDestination.Food("o-6")), viewModel.state.value)
    }

    private fun viewModel(resolver: FakeOrderVerticalResolver, orderId: String) = OrderDeepLinkViewModel(
        savedStateHandle = SavedStateHandle(mapOf("orderId" to orderId)),
        resolver = resolver,
    )
}
