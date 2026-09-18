package uz.mahalla.feature.food.ui.cart

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.food.domain.Cart
import uz.mahalla.feature.food.domain.CartCalculator
import uz.mahalla.feature.food.domain.CartLine
import uz.mahalla.testutil.FakeCartRepository
import uz.mahalla.testutil.FakeDeliveryFeeRepository
import uz.mahalla.testutil.MainDispatcherRule
import uz.mahalla.testutil.cartLine

/**
 * Корзина (эпик 5.2): количество, итог и стоимость доставки.
 *
 * Промокода нет — приложить его к заказу бэкенду нечем (см. `MenuRepository`).
 *
 * Доставка приезжает отдельным запросом с задержкой (issue #179), поэтому
 * тесты, которым она важна, двигают время `advanceUntilIdle()`: без этого
 * проверялось бы состояние до ответа сервера.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CartViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val cartRepository = FakeCartRepository()
    private val deliveryFeeRepository = FakeDeliveryFeeRepository()

    @Test
    fun `the cart is read from the draft, not from the screen`() = runTest {
        seed(cartLine("osh", unitPriceSum = 30_000, quantity = 2))

        val state = viewModel().state.value

        assertEquals(1, state.lines.size)
        assertEquals(60_000L, state.totals.subtotalSum)
        assertEquals("Osh markazi", state.placeName)
        assertTrue(state.isLoaded)
    }

    @Test
    fun `changing the quantity updates the total`() = runTest {
        seed(cartLine("osh", unitPriceSum = 30_000))
        val viewModel = viewModel()

        viewModel.onEvent(CartEvent.QuantityChanged(lineId("osh"), 3))

        assertEquals(90_000L, viewModel.state.value.totals.subtotalSum)
    }

    @Test
    fun `dropping the quantity to zero removes the line`() = runTest {
        seed(cartLine("osh"))
        val viewModel = viewModel()

        viewModel.onEvent(CartEvent.QuantityChanged(lineId("osh"), 0))

        assertTrue(viewModel.state.value.isEmpty)
        assertFalse(viewModel.state.value.canCheckout)
    }

    @Test
    fun `lines with different options are counted separately`() = runTest {
        cartRepository.seed(
            Cart(
                placeId = PLACE_ID,
                placeName = "Osh markazi",
                lines = listOf(
                    cartLine("osh", unitPriceSum = 30_000),
                    cartLine("osh", unitPriceSum = 40_000, optionIds = setOf("large")),
                ),
            ),
        )

        assertEquals(70_000L, viewModel().state.value.totals.subtotalSum)
    }

    @Test
    fun `the cart invents neither a discount nor a delivery fee of its own`() = runTest {
        // Скидку приложить к заказу «Еды» нечем, а доставку называет сервер
        // (issue #179): до его ответа в корзине нулей нет, а не выдуманные
        // числа.
        deliveryFeeRepository.fee = ApiResult.Success(null)
        seed(cartLine("osh", unitPriceSum = 100_000))
        val viewModel = viewModel()

        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(0L, state.totals.discountSum)
        assertEquals(0L, state.totals.deliverySum)
        assertEquals(100_000L, state.totals.totalSum)
        assertFalse(state.showsDelivery)
    }

    @Test
    fun `the delivery fee from the server grows the total and shows its own row`() = runTest {
        // Раньше человек видел в корзине итог без доставки, а платил больше
        // (issue #179).
        deliveryFeeRepository.fee = ApiResult.Success(100)
        seed(cartLine("osh", unitPriceSum = 50_000))
        val viewModel = viewModel()

        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(listOf(50_000L), deliveryFeeRepository.requestedSums)
        assertEquals(100L, state.totals.deliverySum)
        assertEquals(50_100L, state.totals.totalSum)
        assertTrue(state.showsDelivery)
    }

    @Test
    fun `free delivery is not drawn as a zero row`() = runTest {
        // Ноль в строке «Доставка» читался бы как ошибка расчёта.
        deliveryFeeRepository.fee = ApiResult.Success(0)
        seed(cartLine("osh", unitPriceSum = 300_000))
        val viewModel = viewModel()

        advanceUntilIdle()

        assertFalse(viewModel.state.value.showsDelivery)
        assertEquals(300_000L, viewModel.state.value.totals.totalSum)
    }

    @Test
    fun `changing the quantity fires one fee request, not one per tick`() = runTest {
        deliveryFeeRepository.fee = ApiResult.Success(100)
        seed(cartLine("osh", unitPriceSum = 10_000))
        val viewModel = viewModel()
        advanceUntilIdle()
        deliveryFeeRepository.requestedSums.clear()

        viewModel.onEvent(CartEvent.QuantityChanged(lineId("osh"), 2))
        viewModel.onEvent(CartEvent.QuantityChanged(lineId("osh"), 3))
        viewModel.onEvent(CartEvent.QuantityChanged(lineId("osh"), 4))
        advanceUntilIdle()

        assertEquals(listOf(40_000L), deliveryFeeRepository.requestedSums)
        assertEquals(40_100L, viewModel.state.value.totals.totalSum)
    }

    @Test
    fun `a refused fee request leaves the cart checkoutable, without delivery`() = runTest {
        // Экран ошибки вместо корзины из-за необязательного запроса — худшее
        // из решений: оформить заказ всё равно можно.
        deliveryFeeRepository.fee = ApiResult.Failure(ApiError.NoConnection)
        seed(cartLine("osh", unitPriceSum = 50_000))
        val viewModel = viewModel()

        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(50_000L, state.totals.totalSum)
        assertFalse(state.showsDelivery)
        assertTrue(state.canCheckout)
    }

    @Test
    fun `an emptied cart forgets the delivery fee`() = runTest {
        // Доставка пустой корзины — не ноль и не прежняя цена: её нет.
        deliveryFeeRepository.fee = ApiResult.Success(100)
        seed(cartLine("osh", unitPriceSum = 50_000))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onEvent(CartEvent.CartCleared)
        advanceUntilIdle()

        assertNull(viewModel.state.value.deliverySum)
        assertEquals(0L, viewModel.state.value.totals.totalSum)
    }

    @Test
    fun `add more carries the place name back to the menu`() = runTest {
        // Меню не знает названия заведения — в ответе бэкенда его нет.
        seed(cartLine("osh"))
        val viewModel = viewModel()

        viewModel.onEvent(CartEvent.AddMoreClicked)

        assertEquals(CartEffect.OpenMenu(PLACE_ID, "Osh markazi"), viewModel.effects.first())
    }

    @Test
    fun `checkout is not offered for an empty cart`() = runTest {
        val viewModel = viewModel()

        assertFalse(viewModel.state.value.canCheckout)
    }

    private fun seed(vararg lines: CartLine) {
        cartRepository.seed(
            Cart(placeId = PLACE_ID, placeName = "Osh markazi", lines = lines.toList()),
        )
    }

    private fun lineId(itemId: String) = CartCalculator.lineId(itemId, emptySet())

    private fun viewModel() = CartViewModel(
        cartRepository = cartRepository,
        deliveryFeeRepository = deliveryFeeRepository,
        savedStateHandle = SavedStateHandle(mapOf("placeId" to PLACE_ID)),
    )

    private companion object {
        const val PLACE_ID = "place-1"
    }
}
