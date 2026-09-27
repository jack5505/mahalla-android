package uz.mahalla.feature.onboarding.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.R
import uz.mahalla.core.ui.text.OtpFieldState
import uz.mahalla.ui.theme.MahallaTheme

/**
 * PIN онбординга (3.4) без UI-тестов до issue #347: покрыты только нампад
 * ([uz.mahalla.core.ui.components.PinPadTest]) и ViewModel, а не то, что их
 * связывает — попадает ли нажатая цифра в правильное событие и появляется ли
 * «Забыли PIN» ровно на разблокировке.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w393dp-h852dp-mdpi")
class PinScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<PinEvent>()

    @Test
    fun `pressing a digit reports exactly that digit`() {
        setContent(mutableStateOf(PinState(stage = PinStage.Create)))

        compose.onNodeWithContentDescription("5").performClick()

        assertEquals(listOf(PinEvent.DigitPressed('5')), events)
    }

    @Test
    fun `backspace is its own event`() {
        setContent(
            mutableStateOf(
                PinState(stage = PinStage.Create, pin = OtpFieldState(code = "12", length = 6)),
            ),
        )

        compose.onNodeWithContentDescription(backspaceLabel()).performClick()

        assertEquals(listOf(PinEvent.BackspacePressed), events)
    }

    @Test
    fun `forgot pin is offered only when unlocking a saved code`() {
        val state = mutableStateOf(PinState(stage = PinStage.Create))
        setContent(state)
        compose.onNodeWithText(forgotLabel()).assertDoesNotExist()

        compose.runOnIdle { state.value = state.value.copy(stage = PinStage.Confirm) }
        compose.waitForIdle()
        compose.onNodeWithText(forgotLabel()).assertDoesNotExist()

        compose.runOnIdle { state.value = state.value.copy(stage = PinStage.Unlock) }
        compose.waitForIdle()
        compose.onNodeWithText(forgotLabel()).performClick()

        assertEquals(listOf(PinEvent.ForgotPin), events)
    }

    @Test
    fun `a mismatch on confirm keeps the confirm title, not create`() {
        setContent(mutableStateOf(PinState(stage = PinStage.Confirm, error = PinError.MISMATCH)))

        compose.onNodeWithText(text(R.string.onboarding_pin_confirm_title)).assertExists()
        compose.onNodeWithText(text(R.string.onboarding_pin_error_mismatch)).assertExists()
    }

    @Test
    fun `a wrong pin on unlock shows the remaining attempts`() {
        setContent(
            mutableStateOf(
                PinState(stage = PinStage.Unlock, error = PinError.WRONG_PIN, attemptsLeft = 2),
            ),
        )

        compose.onNodeWithText(compose.activity.getString(R.string.onboarding_pin_error_wrong, 2))
            .assertExists()
    }

    private fun setContent(state: MutableState<PinState>) {
        compose.setContent {
            MahallaTheme {
                PinContent(state = state.value, onEvent = { events += it })
            }
        }
        compose.waitForIdle()
    }

    private fun text(res: Int) = compose.activity.getString(res)
    private fun forgotLabel() = compose.activity.getString(R.string.onboarding_pin_forgot)
    private fun backspaceLabel() = compose.activity.getString(R.string.pin_pad_backspace)
}
