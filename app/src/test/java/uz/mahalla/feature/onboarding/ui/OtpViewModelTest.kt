package uz.mahalla.feature.onboarding.ui

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
import uz.mahalla.feature.auth.domain.OtpDeliveryChannel
import uz.mahalla.feature.auth.domain.OtpFailure
import uz.mahalla.feature.auth.domain.VerificationResult
import uz.mahalla.navigation.OtpArgs
import uz.mahalla.testutil.FakeAuthRepository
import uz.mahalla.testutil.FakeTelegramAvailability
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
    private val telegramAvailability = FakeTelegramAvailability()

    private fun viewModel(
        phone: String = PHONE,
        otpToken: String = OTP_TOKEN,
        resendAfterSeconds: Int = 60,
        codeLength: Int = 6,
        channel: String? = null,
    ) = OtpViewModel(
        SavedStateHandle(
            buildMap {
                put(OtpArgs.PHONE, phone)
                put(OtpArgs.OTP_TOKEN, otpToken)
                put(OtpArgs.RESEND_AFTER_SECONDS, resendAfterSeconds)
                put(OtpArgs.CODE_LENGTH, codeLength)
                channel?.let { put(OtpArgs.CHANNEL, it) }
            },
        ),
        authRepository,
        telegramAvailability,
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
        val viewModel = OtpViewModel(SavedStateHandle(), authRepository, telegramAvailability)

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
        authRepository.verifyResult =
            ApiResult.Success(VerificationResult.Authorized(LoginResult(isNewUser = true)))
        val viewModel = viewModel()

        viewModel.onEvent(OtpEvent.CodeChanged("123456"))
        val effect = viewModel.effects.first()

        assertEquals(OtpEffect.Verified(isNewUser = true), effect)
        // Код проверяется по токену испытания, а не по номеру телефона:
        // так его принимает бэкенд (issue #42).
        assertEquals(listOf(OTP_TOKEN to "123456"), authRepository.verifiedCodes)
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

        val state = viewModel.state.value
        assertEquals(
            "Raqam bloklangan, qo'llab-quvvatlashga murojaat qiling",
            state.apiFailure?.serverMessage,
        )
        // Текст сервера идёт под полем вместо «код неверный», и блок его не
        // повторяет: два объяснения одного отказа в двух местах — хуже, чем
        // одно.
        assertEquals(
            "Raqam bloklangan, qo'llab-quvvatlashga murojaat qiling",
            state.fieldError,
        )
        assertFalse("тот же текст вторым сообщением не показываем", state.showApiMessage)
    }

    @Test
    fun `a network failure keeps the message in its own block`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        // Сервер не отвечал — под полем писать нечего, текст остаётся в блоке.
        authRepository.verifyResult = ApiResult.Failure(ApiError.NoConnection)
        val viewModel = viewModel()

        viewModel.onEvent(OtpEvent.CodeChanged("000000"))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertNull(state.fieldError)
        assertTrue(state.showApiMessage)
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
            OtpChallenge(otpToken = "otp-2", codeLength = 4, resendAfterSeconds = 20),
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
    fun `the telegram channel from the route reaches the screen`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        // issue #54: без этого экран писал «код отправлен на +998…», а код в
        // это время лежал в Telegram — человек ждал SMS, которого не будет.
        val state = viewModel(channel = OtpDeliveryChannel.Telegram.name).state.value

        assertTrue(state.isTelegramChannel)
        assertTrue("Telegram установлен — кнопку показываем", state.canOpenTelegram)
    }

    @Test
    fun `an unknown channel is treated as sms`() = runTest(mainDispatcherRule.dispatcher) {
        // Обещать Telegram там, где его может не быть, хуже, чем промолчать.
        assertFalse(viewModel(channel = "CARRIER_PIGEON").state.value.isTelegramChannel)
        assertFalse(viewModel().state.value.isTelegramChannel)
    }

    @Test
    fun `without telegram installed the screen offers no button`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        telegramAvailability.packageName = null
        val viewModel = viewModel(channel = OtpDeliveryChannel.Telegram.name)

        val state = viewModel.state.value
        assertTrue("объяснение всё равно нужно — SMS не придёт", state.isTelegramChannel)
        assertFalse("открывать нечего", state.canOpenTelegram)

        // Кнопки нет, но событие могло прийти из старой композиции.
        val effects = mutableListOf<OtpEffect>()
        val collector = launch { viewModel.effects.toList(effects) }
        viewModel.onEvent(OtpEvent.OpenTelegramRequested)
        advanceUntilIdle()
        assertTrue("открывать нечего — эффекта нет", effects.isEmpty())
        collector.cancel()
    }

    @Test
    fun `opening telegram addresses the installed client`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = viewModel(channel = OtpDeliveryChannel.Telegram.name)

        viewModel.onEvent(OtpEvent.OpenTelegramRequested)

        assertEquals(
            OtpEffect.OpenTelegram(FakeTelegramAvailability.DEFAULT_PACKAGE),
            viewModel.effects.first(),
        )
    }

    @Test
    fun `resend updates the channel because the server picks it anew`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        // Первый код ушёл в SMS, повторный сервер отправил боту — подпись
        // экрана обязана переехать вместе с ним.
        authRepository.requestCodeResult = ApiResult.Success(
            OtpChallenge(otpToken = "otp-2", channel = OtpDeliveryChannel.Telegram),
        )
        val viewModel = viewModel(resendAfterSeconds = 0)
        assertFalse(viewModel.state.value.isTelegramChannel)

        viewModel.onEvent(OtpEvent.Resend)
        runCurrent()

        assertTrue(viewModel.state.value.isTelegramChannel)
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

    @Test
    fun `resend replaces the otp token`() = runTest(mainDispatcherRule.dispatcher) {
        // Старый токен бэкенд гасит вместе с отправкой нового кода — проверять
        // по нему было бы гарантированной ошибкой.
        authRepository.requestCodeResult = ApiResult.Success(OtpChallenge(otpToken = "otp-2"))
        val viewModel = viewModel(resendAfterSeconds = 0)

        viewModel.onEvent(OtpEvent.Resend)
        runCurrent()
        viewModel.onEvent(OtpEvent.CodeChanged("123456"))
        advanceUntilIdle()

        assertEquals("otp-2", viewModel.state.value.otpToken)
        assertEquals(listOf("otp-2" to "123456"), authRepository.verifiedCodes)
    }

    private companion object {
        const val PHONE = "+998901234567"
        const val OTP_TOKEN = "otp-1"
    }
}
