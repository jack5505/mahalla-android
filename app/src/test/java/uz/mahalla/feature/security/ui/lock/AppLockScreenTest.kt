package uz.mahalla.feature.security.ui.lock

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.R
import uz.mahalla.core.ui.text.OtpFieldState
import uz.mahalla.data.security.BiometricStatus
import uz.mahalla.ui.theme.MahallaTheme

/**
 * Оверлей блокировки (issue #102) без UI-тестов до issue #347: экран рисуется
 * поверх всего приложения, и разошедшийся ввод здесь запирает человека вне
 * его же кошелька и заказов.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w393dp-h852dp-mdpi")
class AppLockScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<AppLockEvent>()

    @Test
    fun `typing a code reports it as a single event`() {
        setContent(mutableStateOf(AppLockState()))

        compose.onNodeWithContentDescription(pinDescription(6)).performTextInput("123456")

        assertEquals(listOf(AppLockEvent.PinChanged("123456")), events)
    }

    @Test
    fun `the pin field length follows the saved pin, not a fixed constant`() {
        setContent(mutableStateOf(AppLockState(pin = OtpFieldState(length = 4))))

        compose.onNodeWithContentDescription(pinDescription(4)).assertExists()
        compose.onNodeWithContentDescription(pinDescription(6)).assertDoesNotExist()
    }

    @Test
    fun `biometric button is hidden when there is nothing enrolled`() {
        setContent(
            mutableStateOf(
                AppLockState(biometricEnabled = true, biometricStatus = BiometricStatus.NotEnrolled),
            ),
        )

        compose.onNodeWithText(biometricLabel()).assertDoesNotExist()
    }

    @Test
    fun `biometric button requests the prompt when it can be used`() {
        setContent(
            mutableStateOf(
                AppLockState(biometricEnabled = true, biometricStatus = BiometricStatus.Available),
            ),
        )

        compose.onNodeWithText(biometricLabel()).performClick()

        assertEquals(listOf(AppLockEvent.BiometricRequested), events)
    }

    @Test
    fun `forgot pin always reports its own event`() {
        setContent(mutableStateOf(AppLockState()))

        compose.onNodeWithText(forgotLabel()).performClick()

        assertEquals(listOf(AppLockEvent.ForgotPin), events)
    }

    private fun setContent(state: MutableState<AppLockState>) {
        events.clear()
        compose.setContent {
            MahallaTheme {
                AppLockContent(state = state.value, onEvent = { events += it })
            }
        }
        compose.waitForIdle()
    }

    private fun pinDescription(length: Int) =
        compose.activity.getString(R.string.pin_input_description, length)

    private fun biometricLabel() = compose.activity.getString(R.string.app_lock_biometric)
    private fun forgotLabel() = compose.activity.getString(R.string.onboarding_pin_forgot)
}
