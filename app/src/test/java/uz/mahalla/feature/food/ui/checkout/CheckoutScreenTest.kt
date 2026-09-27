package uz.mahalla.feature.food.ui.checkout

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.R
import uz.mahalla.feature.food.domain.CartLine
import uz.mahalla.feature.food.domain.CartTotals
import uz.mahalla.feature.food.domain.CheckoutForm
import uz.mahalla.ui.theme.MahallaTheme

/**
 * Чекаут еды (issue #334): релиз принимает только наличные, выбора оплаты на
 * экране нет. Регресс-тест на случай, если кто-то в будущем вернёт кошелёк на
 * экран раньше, чем бэкенд начнёт принимать `WALLET` (issue #347).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w393dp-h852dp-mdpi")
class CheckoutScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<CheckoutEvent>()

    @Test
    fun `payment is a single disabled cash card`() {
        setContent()

        compose.onNodeWithText(cashLabel()).assertIsSelected().assertIsNotEnabled()
        compose.onNodeWithText(walletLabel()).assertDoesNotExist()
    }

    @Test
    fun `submit reports the event exactly once`() {
        setContent()

        compose.onNodeWithText(submitLabel()).performClick()

        assertEquals(listOf(CheckoutEvent.SubmitClicked), events)
    }

    private fun setContent(state: CheckoutState = stateWithOneLine()) {
        compose.setContent {
            MahallaTheme {
                CheckoutContent(state = state, onEvent = { events += it }, onBack = {})
            }
        }
        compose.waitForIdle()
    }

    private fun stateWithOneLine() = CheckoutState(
        placeId = "p-1",
        placeName = "Osh markazi",
        lines = listOf(
            CartLine(id = "osh", itemId = "osh", name = "Osh", unitPriceSum = 30_000, quantity = 1),
        ),
        form = CheckoutForm(),
        totals = CartTotals(subtotalSum = 30_000),
    )

    private fun cashLabel() = compose.activity.getString(R.string.checkout_payment_cash)
    private fun walletLabel() = compose.activity.getString(R.string.checkout_payment_wallet)
    private fun submitLabel() = compose.activity.getString(R.string.checkout_submit)
}
