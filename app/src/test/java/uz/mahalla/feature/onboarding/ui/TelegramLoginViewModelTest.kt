package uz.mahalla.feature.onboarding.ui

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
import uz.mahalla.feature.auth.domain.TelegramChallenge
import uz.mahalla.feature.auth.domain.TelegramLoginState
import uz.mahalla.testutil.FakeAuthRepository
import uz.mahalla.testutil.FakeTelegramAvailability
import uz.mahalla.testutil.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Вход через Telegram-бот (issue #46).
 *
 * Время виртуальное: опрос ждёт паузами до пяти секунд, а токен живёт пять
 * минут — реальные задержки сделали бы тест на несколько минут.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TelegramLoginViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val authRepository = FakeAuthRepository()
    private val telegramAvailability = FakeTelegramAvailability()

    /**
     * Часы двигаются вместе с виртуальным временем корутин: срок жизни токена
     * ViewModel считает по ним, а паузы опроса — по планировщику, и разъехаться
     * они не должны.
     */
    private val scheduler get() = mainDispatcherRule.dispatcher.scheduler
    private val clock: Clock get() = object : Clock() {
        override fun instant(): Instant = Instant.ofEpochMilli(scheduler.currentTime)
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?): Clock = this
    }

    private fun viewModel() =
        TelegramLoginViewModel(authRepository, telegramAvailability, clock)

    @Test
    fun `screen opens the bot right away and starts polling`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = viewModel()
        val effect = viewModel.effects.first()

        assertEquals(1, authRepository.telegramStartCount)
        assertEquals(
            TelegramEffect.OpenBot(
                url = FakeAuthRepository.DEFAULT_BOT_URL,
                packageName = FakeTelegramAvailability.DEFAULT_PACKAGE,
            ),
            effect,
        )
        assertEquals(TelegramStatus.WAITING, viewModel.state.value.status)

        advanceTimeBy(10_000)
        assertTrue("опрос идёт", authRepository.telegramChecks.isNotEmpty())
        val token = FakeAuthRepository.DEFAULT_DEEP_LINK_TOKEN
        assertTrue(authRepository.telegramChecks.all { it == token })
    }

    @Test
    fun `pressing Start confirms the login`() = runTest(mainDispatcherRule.dispatcher) {
        authRepository.telegramCheckResults = listOf(
            ApiResult.Success(TelegramLoginState.Pending),
            ApiResult.Success(TelegramLoginState.Pending),
            ApiResult.Success(
                TelegramLoginState.Confirmed(login = LoginResult(isNewUser = true)),
            ),
        )

        val viewModel = viewModel()
        val effects = mutableListOf<TelegramEffect>()
        backgroundScope.launch { viewModel.effects.toList(effects) }

        advanceTimeBy(30_000)

        assertTrue(
            "вход подтверждён",
            effects.contains(TelegramEffect.Confirmed(isNewUser = true)),
        )
        assertTrue("сессия сохранена", authRepository.isAuthorized.first())
        assertEquals(TelegramStatus.CONFIRMED, viewModel.state.value.status)
        assertFalse("ждать больше нечего", viewModel.state.value.isWaiting)
    }

    /**
     * Подтверждение чаще всего случается, пока экран в фоне — человек в этот
     * момент в Telegram. Возвращение должно проверяться сразу, а не после
     * очередной паузы.
     */
    @Test
    fun `returning to the screen checks immediately`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = viewModel()
        // Не advanceUntilIdle: опрос — бесконечный цикл, и «до простоя» здесь
        // означает промотать все пять минут жизни токена до самого истечения.
        advanceTimeBy(5_000)
        val before = authRepository.telegramChecks.size

        viewModel.onEvent(TelegramEvent.ScreenResumed)
        runCurrent()

        assertEquals(
            "проверка ушла без паузы",
            before + 1,
            authRepository.telegramChecks.size,
        )
    }

    @Test
    fun `expired token stops the polling and offers a retry`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        authRepository.telegramStartResult = ApiResult.Success(
            TelegramChallenge(
                deepLinkToken = "short-lived",
                botUrl = FakeAuthRepository.DEFAULT_BOT_URL,
                expiresInSeconds = 10,
            ),
        )

        val viewModel = viewModel()
        advanceTimeBy(11_000)
        advanceUntilIdle()

        assertEquals(TelegramStatus.EXPIRED, viewModel.state.value.status)
        assertTrue(viewModel.state.value.canRetry)

        val afterExpiry = authRepository.telegramChecks.size
        advanceTimeBy(60_000)
        assertEquals("опрос остановлен", afterExpiry, authRepository.telegramChecks.size)
    }

    @Test
    fun `retry asks for a brand new token`() = runTest(mainDispatcherRule.dispatcher) {
        authRepository.telegramStartResult = ApiResult.Failure(ApiError.NoConnection)
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(TelegramStatus.FAILED, viewModel.state.value.status)

        authRepository.telegramStartResult = ApiResult.Success(
            TelegramChallenge(
                deepLinkToken = "second-token",
                botUrl = FakeAuthRepository.DEFAULT_BOT_URL,
            ),
        )
        viewModel.onEvent(TelegramEvent.RetryRequested)
        advanceTimeBy(10_000)

        assertEquals(2, authRepository.telegramStartCount)
        assertTrue(
            "опрашиваем новый токен, а не прежний",
            authRepository.telegramChecks.all { it == "second-token" },
        )
    }

    /**
     * Пока человек нажимает Start, телефон часто теряет сеть. Обрывать из-за
     * этого живой токен нельзя.
     */
    @Test
    fun `a lost connection does not abort the attempt`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        authRepository.telegramCheckResults = listOf(
            ApiResult.Failure(ApiError.NoConnection),
            ApiResult.Failure(ApiError.Http(503, null)),
            ApiResult.Success(TelegramLoginState.Confirmed(login = LoginResult(false))),
        )

        val viewModel = viewModel()
        val effects = mutableListOf<TelegramEffect>()
        backgroundScope.launch { viewModel.effects.toList(effects) }
        advanceTimeBy(30_000)

        assertTrue(effects.contains(TelegramEffect.Confirmed(isNewUser = false)))
        assertNull(viewModel.state.value.apiFailure)
    }

    @Test
    fun `a refusal from the server stops the polling and is shown`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val failure = ApiFailure(
            error = ApiError.Http(400, null),
            server = ServerError(httpCode = 400, code = "TG_TOKEN_INVALID", message = "no"),
        )
        authRepository.telegramCheckResults = listOf(ApiResult.Failure(failure))

        val viewModel = viewModel()
        advanceTimeBy(30_000)

        assertEquals(TelegramStatus.FAILED, viewModel.state.value.status)
        assertEquals(failure, viewModel.state.value.apiFailure)
        assertEquals("повторять отказ бессмысленно", 1, authRepository.telegramChecks.size)
    }

    /**
     * Telegram узнал человека, но номер аккаунта не подтверждён: сессии нет,
     * дальше обычный SMS-путь.
     *
     * Раньше это был молчаливый одноразовый эффект при статусе `WAITING` —
     * экран продолжал крутить «ждём подтверждения», а `deepLinkToken` был уже
     * обнулён, то есть выйти из состояния было нельзя (issue #49).
     */
    @Test
    fun `unverified phone stops the spinner and explains itself`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        authRepository.telegramCheckResults = listOf(
            ApiResult.Success(
                TelegramLoginState.Confirmed(
                    login = LoginResult(isNewUser = true),
                    requiresPhoneVerify = true,
                    phone = "+998937555505",
                ),
            ),
        )

        val viewModel = viewModel()
        val effects = mutableListOf<TelegramEffect>()
        backgroundScope.launch { viewModel.effects.toList(effects) }
        advanceTimeBy(10_000)

        val state = viewModel.state.value
        assertEquals(TelegramStatus.PHONE_VERIFY, state.status)
        assertTrue("кнопка перехода на SMS — главная", state.needsPhoneVerify)
        assertFalse("крутилке здесь делать нечего", state.isWaiting)
        assertEquals("номер называем явно", "+998937555505", state.phone)
        assertFalse("полуавторизованной сессии быть не должно", authRepository.isAuthorized.first())
        assertFalse(
            "на форму номера уводит тап, а не само приложение",
            effects.contains(TelegramEffect.SwitchToSms),
        )
    }

    /**
     * После подтверждения опрашивать больше нечего: токен потрачен. Возврат на
     * экран не должен ни перезапускать опрос, ни возвращать крутилку.
     */
    @Test
    fun `returning after the phone verify request changes nothing`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        authRepository.telegramCheckResults = listOf(
            ApiResult.Success(
                TelegramLoginState.Confirmed(
                    login = LoginResult(isNewUser = true),
                    requiresPhoneVerify = true,
                ),
            ),
        )

        val viewModel = viewModel()
        advanceTimeBy(10_000)
        val checks = authRepository.telegramChecks.size

        viewModel.onEvent(TelegramEvent.ScreenResumed)
        advanceTimeBy(30_000)

        assertEquals("опрос остановлен", checks, authRepository.telegramChecks.size)
        assertEquals(TelegramStatus.PHONE_VERIFY, viewModel.state.value.status)
    }

    /** С экрана «подтвердите номер» есть ровно один шаг — и он работает. */
    @Test
    fun `phone verify screen leads to sms`() = runTest(mainDispatcherRule.dispatcher) {
        authRepository.telegramCheckResults = listOf(
            ApiResult.Success(
                TelegramLoginState.Confirmed(
                    login = LoginResult(isNewUser = true),
                    requiresPhoneVerify = true,
                ),
            ),
        )

        val viewModel = viewModel()
        val effects = mutableListOf<TelegramEffect>()
        backgroundScope.launch { viewModel.effects.toList(effects) }
        advanceTimeBy(10_000)

        viewModel.onEvent(TelegramEvent.SmsRequested)
        runCurrent()

        assertTrue(effects.contains(TelegramEffect.SwitchToSms))
    }

    /**
     * Возврат из Telegram — самый частый момент подтверждения, и он же отменяет
     * текущий опрос. Отменённым не должен оказаться тот опрос, который
     * подтверждение уже получил: токен одноразовый, второй раз его никто не
     * подтвердит.
     */
    @Test
    fun `a resume does not swallow a confirmation already received`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        authRepository.telegramCheckResults = listOf(
            ApiResult.Success(TelegramLoginState.Pending),
            ApiResult.Success(
                TelegramLoginState.Confirmed(login = LoginResult(isNewUser = false)),
            ),
        )

        val viewModel = viewModel()
        val effects = mutableListOf<TelegramEffect>()
        backgroundScope.launch { viewModel.effects.toList(effects) }

        // Первый опрос отвечает `Pending`, второй — подтверждением; событие
        // возврата приходит ровно в тот же момент.
        advanceTimeBy(2_000)
        viewModel.onEvent(TelegramEvent.ScreenResumed)
        advanceTimeBy(10_000)

        assertEquals(TelegramStatus.CONFIRMED, viewModel.state.value.status)
        assertTrue(effects.contains(TelegramEffect.Confirmed(isNewUser = false)))
        assertTrue("сессия сохранена", authRepository.isAuthorized.first())
    }

    @Test
    fun `user can leave for sms at any moment`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        val effects = mutableListOf<TelegramEffect>()
        backgroundScope.launch { viewModel.effects.toList(effects) }
        advanceTimeBy(5_000)

        viewModel.onEvent(TelegramEvent.SmsRequested)
        runCurrent()

        assertTrue(effects.contains(TelegramEffect.SwitchToSms))
    }
}
