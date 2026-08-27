package uz.mahalla.feature.onboarding.ui

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
import uz.mahalla.feature.auth.domain.LoginResult
import uz.mahalla.feature.auth.domain.OtpChallenge
import uz.mahalla.feature.auth.domain.OtpFailure
import uz.mahalla.navigation.OtpArgs
import uz.mahalla.testutil.FakeAuthRepository
import uz.mahalla.testutil.MainDispatcherRule

/**
 * Экран кода (3.3): таймер повтора, автоотправка по последней цифре и
 * ветвление ошибок.
 *
 * Таймер идёт по виртуальному времени `runTest` — тест не ждёт настоящую
 * минуту.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OtpViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val authRepository = FakeAuthRepository()

    private fun viewModel(
        phone: String = PHONE,
        resendAfterSeconds: Int = 60,
        codeLength: Int = 6,
    ) = OtpViewModel(
        SavedStateHandle(
            mapOf(
                OtpArgs.PHONE to phone,
                OtpArgs.RESEND_AFTER_SECONDS to resendAfterSeconds,
                OtpArgs.CODE_LENGTH to codeLength,
            ),
        ),
        authRepository,
    )

    @Test
    fun `initial state comes from the route arguments`() = runTest(mainDispatcherRule.dispatcher) {
        val state = viewModel(resendAfterSeconds = 45, codeLength = 4).state.value

        assertEquals(PHONE, state.phone)
        assertEquals(4, state.code.length)
        assertEquals(45, state.resendInSeconds)
        assertFalse("пока идёт отсчёт, повтор недоступен", state.canResend)
    }

    @Test
    fun `missing arguments fall back to defaults instead of crashing`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = OtpViewModel(SavedStateHandle(), authRepository)

        assertEquals("", viewModel.state.value.phone)
        assertEquals(OtpChallenge.DEFAULT_CODE_LENGTH, viewModel.state.value.code.length)
        assertEquals(OtpChallenge.DEFAULT_RESEND_SECONDS, viewModel.state.value.resendInSeconds)
    }

    @Test
    fun `countdown unlocks resend exactly once it reaches zero`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = viewModel(resendAfterSeconds = 3)

        advanceTimeBy(2_100)
        assertEquals(1, viewModel.state.value.resendInSeconds)
        assertFalse(viewModel.state.value.canResend)

        advanceTimeBy(1_000)
        assertEquals(0, viewModel.state.value.resendInSeconds)
        assertTrue(viewModel.state.value.canResend)
    }

    @Test
    fun `completing the code verifies it without an extra tap`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        authRepository.verifyResult = ApiResult.Success(LoginResult(isNewUser = true))
        val viewModel = viewModel()

        viewModel.onEvent(OtpEvent.CodeChanged("123456"))
        val effect = viewModel.effects.first()

        assertEquals(OtpEffect.Verified(isNewUser = true), effect)
        assertEquals(listOf(PHONE to "123456"), authRepository.verifiedCodes)
    }

    @Test
    fun `an incomplete code is not sent anywhere`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()

        viewModel.onEvent(OtpEvent.CodeChanged("12345"))
        advanceUntilIdle()

        assertTrue(authRepository.verifiedCodes.isEmpty())
        assertFalse(viewModel.state.value.canSubmit)
    }

    @Test
    fun `a wrong code clears the field and keeps the screen usable`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        authRepository.verifyResult = ApiResult.Failure(ApiError.Unauthorized)
        val viewModel = viewModel()

        viewModel.onEvent(OtpEvent.CodeChanged("000000"))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(OtpFailure.InvalidCode, state.failure)
        assertEquals("", state.code.code)
        assertTrue(state.code.isError)
        assertFalse("ввод не блокируется — код можно набрать снова", state.inputBlocked)
        assertNull("«код неверный» уже написано под полем — второй раз незачем", state.apiFailure)
    }

    @Test
    fun `an explanation from the backend is shown even when the code is blamed`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        // issue #34: под полем стояло бы «код неверный», хотя сервер сказал
        // совсем другое — этот текст и показываем.
        authRepository.verifyResult = ApiResult.Failure(
            ApiFailure(
                error = ApiError.Unauthorized,
                server = ServerError(
                    httpCode = 401,
                    code = "PHONE_BLOCKED",
                    message = "Raqam bloklangan, qo'llab-quvvatlashga murojaat qiling",
                ),
            ),
        )
        val viewModel = viewModel()

        viewModel.onEvent(OtpEvent.CodeChanged("000000"))
        advanceUntilIdle()

        assertEquals(
            "Raqam bloklangan, qo'llab-quvvatlashga murojaat qiling",
            viewModel.state.value.apiFailure?.serverMessage,
        )
    }

    @Test
    fun `spent attempts block the input until a new code arrives`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        authRepository.verifyResult = ApiResult.Failure(ApiError.Http(429, null))
        val viewModel = viewModel(resendAfterSeconds = 1)

        viewModel.onEvent(OtpEvent.CodeChanged("111111"))
        advanceUntilIdle()
        assertEquals(OtpFailure.TooManyAttempts, viewModel.state.value.failure)
        assertTrue(viewModel.state.value.inputBlocked)

        // Ввод действительно заблокирован: новые цифры не принимаются.
        viewModel.onEvent(OtpEvent.CodeChanged("222222"))
        advanceUntilIdle()
        assertEquals(1, authRepository.verifiedCodes.size)

        advanceTimeBy(1_100)
        viewModel.onEvent(OtpEvent.Resend)
        advanceUntilIdle()
        assertNull(viewModel.state.value.failure)
        assertFalse(viewModel.state.value.inputBlocked)
    }

    @Test
    fun `a network failure keeps the typed digits`() = runTest(mainDispatcherRule.dispatcher) {
        authRepository.verifyResult = ApiResult.Failure(ApiError.NoConnection)
        val viewModel = viewModel()

        viewModel.onEvent(OtpEvent.CodeChanged("123456"))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(OtpFailure.Network, state.failure)
        assertEquals("перенабирать код из-за пропавшей сети незачем", "123456", state.code.code)
        assertEquals(ApiError.NoConnection, state.apiFailure?.error)
    }

    @Test
    fun `resend is ignored while the countdown is running`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = viewModel(resendAfterSeconds = 30)

        viewModel.onEvent(OtpEvent.Resend)
        advanceUntilIdle()

        assertTrue(authRepository.requestedPhones.isEmpty())
    }

    @Test
    fun `resend restarts the countdown with the new server value`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        authRepository.requestCodeResult = ApiResult.Success(
            OtpChallenge(codeLength = 4, resendAfterSeconds = 20),
        )
        val viewModel = viewModel(resendAfterSeconds = 1)
        advanceTimeBy(1_100)

        viewModel.onEvent(OtpEvent.Resend)
        // Только запрос кода, без прокрутки времени: `advanceUntilIdle` домотал
        // бы и новый отсчёт до нуля, и проверять было бы нечего.
        runCurrent()

        val state = viewModel.state.value
        assertEquals(listOf(PHONE), authRepository.requestedPhones)
        assertEquals(20, state.resendInSeconds)
        assertEquals("новый код может быть другой длины", 4, state.code.length)
        assertEquals("", state.code.code)
        assertFalse(state.canResend)

        advanceTimeBy(20_100)
        assertEquals(0, viewModel.state.value.resendInSeconds)
    }

    @Test
    fun `failed resend leaves the timer unlocked and reports the error`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        authRepository.requestCodeResult = ApiResult.Failure(ApiError.Timeout)
        val viewModel = viewModel(resendAfterSeconds = 0)

        viewModel.onEvent(OtpEvent.Resend)
        advanceUntilIdle()

        assertEquals(ApiError.Timeout, viewModel.state.value.apiFailure?.error)
        assertTrue("повторить попытку можно сразу", viewModel.state.value.canResend)
    }

    @Test
    fun `a repeated tap on verify does not double submit`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = viewModel()
        viewModel.onEvent(OtpEvent.CodeChanged("123456"))

        viewModel.onEvent(OtpEvent.Submit)
        advanceUntilIdle()

        assertEquals(1, authRepository.verifiedCodes.size)
    }

    private companion object {
        const val PHONE = "+998901234567"
    }
}
