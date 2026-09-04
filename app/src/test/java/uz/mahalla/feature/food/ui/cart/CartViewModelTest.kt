package uz.mahalla.feature.food.ui.cart

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
import uz.mahalla.feature.food.domain.Cart
import uz.mahalla.feature.food.domain.CartCalculator
import uz.mahalla.feature.food.domain.CartLine
import uz.mahalla.testutil.FakeCartRepository
import uz.mahalla.testutil.MainDispatcherRule
import uz.mahalla.testutil.cartLine

/**
 * Корзина (эпик 5.2): количество и итог.
 *
 * Промокода нет — приложить его к заказу бэкенду нечем (см. `MenuRepository`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CartViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val cartRepository = FakeCartRepository()

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
    fun `the cart shows no discount and no delivery of its own`() = runTest {
        // И то и другое называет сервер при оформлении: показать здесь
        // придуманное число значит соврать про деньги.
        seed(cartLine("osh", unitPriceSum = 100_000))

        val totals = viewModel().state.value.totals

        assertEquals(0L, totals.discountSum)
        assertEquals(0L, totals.deliverySum)
        assertEquals(100_000L, totals.totalSum)
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
        savedStateHandle = SavedStateHandle(mapOf("placeId" to PLACE_ID)),
    )

    private companion object {
        const val PLACE_ID = "place-1"
    }
}
