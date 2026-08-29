package uz.mahalla.feature.onboarding.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.ServerError
import uz.mahalla.feature.auth.domain.OtpChallenge
import uz.mahalla.feature.onboarding.domain.PhoneNumberValidator
import uz.mahalla.testutil.FakeAuthRepository
import uz.mahalla.testutil.FakeTelegramAvailability
import uz.mahalla.testutil.MainDispatcherRule

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneInputViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val authRepository = FakeAuthRepository()
    private val telegramAvailability = FakeTelegramAvailability()

    private fun viewModel() =
        PhoneInputViewModel(PhoneNumberValidator(), authRepository, telegramAvailability)

    @Test
    fun `initial state is empty and cannot be submitted`() {
        val state = viewModel().state.value
        assertEquals("", state.nationalDigits)
        assertEquals("+998", state.formatted)
        assertFalse(state.canSubmit)
        assertNull(state.error)
    }

    @Test
    fun `typing formats the number but consent still blocks submit`() {
        val viewModel = viewModel()

        viewModel.onEvent(PhoneInputEvent.PhoneChanged("+998 90 123 45"))
        assertEquals("+998 90 123 45", viewModel.state.value.formatted)
        assertFalse("номер ещё не полный", viewModel.state.value.numberValid)

        viewModel.onEvent(PhoneInputEvent.PhoneChanged("+998 90 123 45 67"))
        assertEquals("901234567", viewModel.state.value.nationalDigits)
        assertTrue(viewModel.state.value.numberValid)
        assertFalse("оферта не отмечена", viewModel.state.value.canSubmit)

        viewModel.onEvent(PhoneInputEvent.ConsentChanged(true))
        assertTrue(viewModel.state.value.canSubmit)
    }

    @Test
    fun `submitting an invalid number shows an error and skips the network`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = viewModel()
        viewModel.onEvent(PhoneInputEvent.ConsentChanged(true))
        viewModel.onEvent(PhoneInputEvent.PhoneChanged("+998 10 123 45 67"))

        viewModel.onEvent(PhoneInputEvent.Submit)
        advanceUntilIdle()

        assertEquals(PhoneInputError.INVALID_NUMBER, viewModel.state.value.error)
        assertTrue("код не запрашивался", authRepository.requestedPhones.isEmpty())
    }

    @Test
    fun `submitting without consent does not send an sms`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onEvent(PhoneInputEvent.PhoneChanged("+998 90 123 45 67"))

        viewModel.onEvent(PhoneInputEvent.Submit)
        advanceUntilIdle()

        assertEquals(PhoneInputError.CONSENT_REQUIRED, viewModel.state.value.error)
        // SMS платное: отправить код и потом попросить галочку — потраченные деньги.
        assertTrue(authRepository.requestedPhones.isEmpty())
    }

    @Test
    fun `accepting consent clears the consent error`() {
        val viewModel = viewModel()
        viewModel.onEvent(PhoneInputEvent.PhoneChanged("+998 90 123 45 67"))
        viewModel.onEvent(PhoneInputEvent.Submit)
        assertEquals(PhoneInputError.CONSENT_REQUIRED, viewModel.state.value.error)

        viewModel.onEvent(PhoneInputEvent.ConsentChanged(true))

        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `editing the number clears the error`() {
        val viewModel = viewModel()
        viewModel.onEvent(PhoneInputEvent.ConsentChanged(true))
        viewModel.onEvent(PhoneInputEvent.PhoneChanged("+998 10 123 45 67"))
        viewModel.onEvent(PhoneInputEvent.Submit)

        viewModel.onEvent(PhoneInputEvent.PhoneChanged("+998 90 123 45 67"))

        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `successful request emits the challenge and stops the spinner`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val challenge = OtpChallenge(otpToken = "otp-1", codeLength = 4, resendAfterSeconds = 30)
        authRepository.requestCodeResult = ApiResult.Success(challenge)
        val viewModel = viewModel()
        viewModel.onEvent(PhoneInputEvent.PhoneChanged("+998 90 123 45 67"))
        viewModel.onEvent(PhoneInputEvent.ConsentChanged(true))

        viewModel.onEvent(PhoneInputEvent.Submit)
        val effect = viewModel.effects.first()

        assertEquals(PhoneInputEffect.CodeRequested("+998901234567", challenge), effect)
        assertEquals(listOf("+998901234567"), authRepository.requestedPhones)
        assertFalse(viewModel.state.value.submitting)
    }

    @Test
    fun `network failure is shown and the button becomes clickable again`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        authRepository.requestCodeResult = ApiResult.Failure(ApiError.NoConnection)
        val viewModel = viewModel()
        viewModel.onEvent(PhoneInputEvent.PhoneChanged("+998 90 123 45 67"))
        viewModel.onEvent(PhoneInputEvent.ConsentChanged(true))

        viewModel.onEvent(PhoneInputEvent.Submit)
        advanceUntilIdle()

        assertEquals(ApiError.NoConnection, viewModel.state.value.apiFailure?.error)
        assertFalse(viewModel.state.value.submitting)
        assertTrue("повторная отправка возможна", viewModel.state.value.canSubmit)
    }

    @Test
    fun `the explanation of the backend reaches the state`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        // issue #34: 403 сам по себе значит «нет прав», а бэкенд объяснил, что
        // именно включить. Именно его текст и должен доехать до экрана.
        authRepository.requestCodeResult = ApiResult.Failure(
            ApiFailure(
                error = ApiError.Forbidden,
                server = ServerError(
                    httpCode = 403,
                    code = "GEO_PERMISSION_REQUIRED",
                    message = "Joylashuv ruxsatini yoqing",
                    requestLine = "POST http://localhost/auth/otp/request",
                ),
            ),
        )
        val viewModel = viewModel()
        viewModel.onEvent(PhoneInputEvent.PhoneChanged("+998 90 123 45 67"))
        viewModel.onEvent(PhoneInputEvent.ConsentChanged(true))

        viewModel.onEvent(PhoneInputEvent.Submit)
        advanceUntilIdle()

        val failure = viewModel.state.value.apiFailure
        assertEquals("Joylashuv ruxsatini yoqing", failure?.serverMessage)
        assertEquals("GEO_PERMISSION_REQUIRED", failure?.server?.code)
    }

    @Test
    fun `retyping the number hides the previous error`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        authRepository.requestCodeResult = ApiResult.Failure(ApiError.NoConnection)
        val viewModel = viewModel()
        viewModel.onEvent(PhoneInputEvent.PhoneChanged("+998 90 123 45 67"))
        viewModel.onEvent(PhoneInputEvent.ConsentChanged(true))
        viewModel.onEvent(PhoneInputEvent.Submit)
        advanceUntilIdle()

        viewModel.onEvent(PhoneInputEvent.PhoneChanged("+998 90 123 45 68"))

        assertNull("ошибка про прошлый номер повиснуть не должна", viewModel.state.value.apiFailure)
    }

    @Test
    fun `a second tap while the request is in flight is ignored`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = viewModel()
        viewModel.onEvent(PhoneInputEvent.PhoneChanged("+998 90 123 45 67"))
        viewModel.onEvent(PhoneInputEvent.ConsentChanged(true))

        viewModel.onEvent(PhoneInputEvent.Submit)
        viewModel.onEvent(PhoneInputEvent.Submit)
        advanceUntilIdle()

        assertEquals(1, authRepository.requestedPhones.size)
    }

    @Test
    fun `offer link is opened through an effect`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()

        viewModel.onEvent(PhoneInputEvent.OfferRequested)

        assertEquals(PhoneInputEffect.OpenOffer, viewModel.effects.first())
    }
}
