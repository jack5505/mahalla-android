package uz.mahalla.feature.food.ui.cart

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import uz.mahalla.feature.food.domain.Cart
import uz.mahalla.feature.food.domain.CartCalculator
import uz.mahalla.feature.food.domain.CartLine
import uz.mahalla.feature.food.domain.PromoCode
import uz.mahalla.feature.food.domain.PromoFailure
import uz.mahalla.feature.food.domain.PromoKind
import uz.mahalla.feature.food.domain.PromoState
import uz.mahalla.testutil.FakeCartRepository
import uz.mahalla.testutil.FakeMenuRepository
import uz.mahalla.testutil.MainDispatcherRule
import uz.mahalla.testutil.cartLine

/** Корзина (эпик 5.2): количество, промокод, итог. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CartViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val cartRepository = FakeCartRepository()
    private val menuRepository = FakeMenuRepository()

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
    fun `an applied promo lowers the total and is shown as applied`() = runTest {
        seed(cartLine("osh", unitPriceSum = 100_000))
        menuRepository.promoResult = ApiResult.Success(
            PromoCode("TEN", PromoKind.Percent, value = 10),
        )
        val viewModel = viewModel()

        viewModel.onEvent(CartEvent.PromoInputChanged("ten"))
        viewModel.onEvent(CartEvent.PromoApplied)

        val state = viewModel.state.value
        assertTrue(state.promo is PromoState.Applied)
        assertEquals(10_000L, state.totals.discountSum)
        assertEquals(90_000L, state.totals.totalSum)
    }

    @Test
    fun `the current subtotal is sent with the promo`() = runTest {
        // «Минимальный заказ» проверяется по актуальному составу, а не по
        // тому, что было в корзине, когда код вводили.
        seed(cartLine("osh", unitPriceSum = 50_000, quantity = 2))
        val viewModel = viewModel()

        viewModel.onEvent(CartEvent.PromoInputChanged("TEN"))
        viewModel.onEvent(CartEvent.PromoApplied)

        assertEquals(100_000L, menuRepository.promoRequests.single().third)
    }

    @Test
    fun `an unknown promo is rejected with its own reason`() = runTest {
        seed(cartLine("osh"))
        menuRepository.promoResult = ApiResult.Failure(ApiError.NotFound)
        val viewModel = viewModel()

        viewModel.onEvent(CartEvent.PromoInputChanged("NOPE"))
        viewModel.onEvent(CartEvent.PromoApplied)

        assertEquals(PromoState.Rejected(PromoFailure.NotFound), viewModel.state.value.promo)
    }

    @Test
    fun `a network problem is not blamed on the code`() = runTest {
        seed(cartLine("osh"))
        menuRepository.promoResult = ApiResult.Failure(ApiError.NoConnection)
        val viewModel = viewModel()

        viewModel.onEvent(CartEvent.PromoInputChanged("TEN"))
        viewModel.onEvent(CartEvent.PromoApplied)

        assertEquals(PromoState.Rejected(PromoFailure.Network), viewModel.state.value.promo)
    }

    @Test
    fun `a valid code that gives no discount here is not applied silently`() = runTest {
        seed(cartLine("osh", unitPriceSum = 30_000))
        menuRepository.promoResult = ApiResult.Success(
            PromoCode("BIG", PromoKind.Fixed, value = 10_000, minOrderSum = 100_000),
        )
        val viewModel = viewModel()

        viewModel.onEvent(CartEvent.PromoInputChanged("BIG"))
        viewModel.onEvent(CartEvent.PromoApplied)

        assertEquals(
            PromoState.Rejected(PromoFailure.MinOrder(100_000)),
            viewModel.state.value.promo,
        )
        assertEquals(0L, viewModel.state.value.totals.discountSum)
    }

    @Test
    fun `editing the code clears the previous rejection`() = runTest {
        seed(cartLine("osh"))
        menuRepository.promoResult = ApiResult.Failure(ApiError.NotFound)
        val viewModel = viewModel()
        viewModel.onEvent(CartEvent.PromoInputChanged("NOPE"))
        viewModel.onEvent(CartEvent.PromoApplied)

        viewModel.onEvent(CartEvent.PromoInputChanged("NOPE2"))

        assertEquals(PromoState.Idle, viewModel.state.value.promo)
    }

    @Test
    fun `an empty code is not sent to the server`() = runTest {
        seed(cartLine("osh"))
        val viewModel = viewModel()

        viewModel.onEvent(CartEvent.PromoApplied)

        assertTrue(menuRepository.promoRequests.isEmpty())
    }

    @Test
    fun `removing the promo returns the full price`() = runTest {
        seed(cartLine("osh", unitPriceSum = 100_000))
        menuRepository.promoResult = ApiResult.Success(
            PromoCode("TEN", PromoKind.Percent, value = 10),
        )
        val viewModel = viewModel()
        viewModel.onEvent(CartEvent.PromoInputChanged("TEN"))
        viewModel.onEvent(CartEvent.PromoApplied)

        viewModel.onEvent(CartEvent.PromoRemoved)

        assertEquals(PromoState.Idle, viewModel.state.value.promo)
        assertEquals(100_000L, viewModel.state.value.totals.totalSum)
    }

    @Test
    fun `checkout is not offered for an empty cart`() = runTest {
        val viewModel = viewModel()

        assertFalse(viewModel.state.value.canCheckout)
    }

    private fun seed(vararg lines: CartLine) {
        cartRepository.seed(
            Cart(
                placeId = PLACE_ID,
                placeName = "Osh markazi",
                deliverySum = 15_000,
                lines = lines.toList(),
            ),
        )
    }

    private fun lineId(itemId: String) = CartCalculator.lineId(itemId, emptySet())

    private fun viewModel() = CartViewModel(
        cartRepository = cartRepository,
        menuRepository = menuRepository,
        savedStateHandle = SavedStateHandle(mapOf("placeId" to PLACE_ID)),
    )

    private companion object {
        const val PLACE_ID = "place-1"
    }
}
