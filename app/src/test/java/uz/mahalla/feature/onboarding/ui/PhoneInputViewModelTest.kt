package uz.mahalla.feature.onboarding.ui

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.feature.onboarding.domain.PhoneNumberValidator

class PhoneInputViewModelTest {

    private fun viewModel() = PhoneInputViewModel(PhoneNumberValidator())

    @Test
    fun `initial state is empty and cannot be submitted`() {
        val state = viewModel().state.value
        assertEquals("", state.nationalDigits)
        assertEquals("+998", state.formatted)
        assertFalse(state.canSubmit)
        assertNull(state.error)
    }

    @Test
    fun `typing formats the number and unlocks submit`() {
        val viewModel = viewModel()

        viewModel.onEvent(PhoneInputEvent.PhoneChanged("+998 90 123 45"))
        assertEquals("+998 90 123 45", viewModel.state.value.formatted)
        assertFalse("номер ещё не полный", viewModel.state.value.canSubmit)

        viewModel.onEvent(PhoneInputEvent.PhoneChanged("+998 90 123 45 67"))
        assertEquals("901234567", viewModel.state.value.nationalDigits)
        assertEquals("+998 90 123 45 67", viewModel.state.value.formatted)
        assertTrue(viewModel.state.value.canSubmit)
    }

    @Test
    fun `submitting an invalid number shows an error instead of an effect`() {
        val viewModel = viewModel()
        viewModel.onEvent(PhoneInputEvent.PhoneChanged("+998 10 123 45 67"))

        viewModel.onEvent(PhoneInputEvent.Submit)

        assertEquals(PhoneInputError.INVALID_NUMBER, viewModel.state.value.error)
        assertFalse(viewModel.state.value.canSubmit)
    }

    @Test
    fun `editing the number clears the error`() {
        val viewModel = viewModel()
        viewModel.onEvent(PhoneInputEvent.PhoneChanged("+998 10 123 45 67"))
        viewModel.onEvent(PhoneInputEvent.Submit)

        viewModel.onEvent(PhoneInputEvent.PhoneChanged("+998 90 123 45 67"))

        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `error can be dismissed explicitly`() {
        val viewModel = viewModel()
        viewModel.onEvent(PhoneInputEvent.Submit)
        assertEquals(PhoneInputError.INVALID_NUMBER, viewModel.state.value.error)

        viewModel.onEvent(PhoneInputEvent.ErrorDismissed)

        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `submitting a valid number emits the code request effect`() = runTest {
        val viewModel = viewModel()
        viewModel.onEvent(PhoneInputEvent.PhoneChanged("+998 90 123 45 67"))

        viewModel.onEvent(PhoneInputEvent.Submit)

        assertEquals(
            PhoneInputEffect.CodeRequested("+998901234567"),
            viewModel.effects.first(),
        )
    }
}
