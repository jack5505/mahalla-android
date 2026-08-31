package uz.mahalla.feature.onboarding.ui

import java.security.GeneralSecurityException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.crash.CrashReporting
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.ServerError
import uz.mahalla.feature.auth.domain.ServerPin
import uz.mahalla.feature.auth.domain.ServerPinChallenge
import uz.mahalla.feature.auth.domain.ServerPinStep
import uz.mahalla.testutil.FakeAuthRepository
import uz.mahalla.testutil.FakeCrashReporter
import uz.mahalla.testutil.FakePinStorage
import uz.mahalla.testutil.MainDispatcherRule

/**
 * PIN (3.4): установка с повтором, ввод сохранённого кода и лимит попыток.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PinViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val authRepository = FakeAuthRepository(initialAuthorized = true)
    private val crashReporter = FakeCrashReporter()

    private fun viewModel(pinStorage: FakePinStorage) = PinViewModel(pinStorage, authRepository)

    @After
    fun tearDown() {
        CrashReporting.reset()
    }

    @Test
    fun `without a stored pin the screen asks to create one`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = viewModel(FakePinStorage())
        advanceUntilIdle()

        assertEquals(PinStage.Create, viewModel.state.value.stage)
        assertEquals(PinState.PIN_LENGTH, viewModel.state.value.pin.length)
    }

    @Test
    fun `with a stored pin the screen asks to enter it`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = viewModel(FakePinStorage(initialPin = "1234"))
        advanceUntilIdle()

        assertEquals(PinStage.Unlock, viewModel.state.value.stage)
    }

    @Test
    fun `a full code moves to the confirmation step with an empty field`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val storage = FakePinStorage()
        val viewModel = viewModel(storage)
        advanceUntilIdle()

        viewModel.onEvent(PinEvent.PinChanged("123456"))
        advanceUntilIdle()

        assertEquals(PinStage.Confirm, viewModel.state.value.stage)
        assertEquals("", viewModel.state.value.pin.code)
        assertEquals("до подтверждения PIN не сохраняется", 0, storage.saveCount)
    }

    @Test
    fun `a matching repeat saves the pin`() = runTest(mainDispatcherRule.dispatcher) {
        val storage = FakePinStorage()
        val viewModel = viewModel(storage)
        advanceUntilIdle()

        viewModel.onEvent(PinEvent.PinChanged("123456"))
        advanceUntilIdle()
        viewModel.onEvent(PinEvent.PinChanged("123456"))
        val effect = viewModel.effects.first()

        assertEquals(PinEffect.PinReady, effect)
        assertEquals("123456", storage.storedPin)
        assertEquals(1, storage.saveCount)
    }

    @Test
    fun `a mismatching repeat starts over with an error`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val storage = FakePinStorage()
        val viewModel = viewModel(storage)
        advanceUntilIdle()

        viewModel.onEvent(PinEvent.PinChanged("123456"))
        advanceUntilIdle()
        viewModel.onEvent(PinEvent.PinChanged("654321"))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(PinStage.Create, state.stage)
        assertEquals(PinError.MISMATCH, state.error)
        assertEquals("", state.pin.code)
        assertNull("ничего не сохранено", storage.storedPin)

        // Первый ввод забыт: повтор старого кода снова уводит на подтверждение.
        viewModel.onEvent(PinEvent.PinChanged("111111"))
        advanceUntilIdle()
        assertEquals(PinStage.Confirm, viewModel.state.value.stage)
    }

    @Test
    fun `the correct pin unlocks the flow`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel(FakePinStorage(initialPin = "1234"))
        advanceUntilIdle()

        viewModel.onEvent(PinEvent.PinChanged("1234"))
        val effect = viewModel.effects.first()

        assertEquals(PinEffect.PinReady, effect)
        assertEquals(PinState.MAX_ATTEMPTS, viewModel.state.value.attemptsLeft)
    }

    @Test
    fun `a wrong pin spends one attempt and clears the field`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = viewModel(FakePinStorage(initialPin = "1234"))
        advanceUntilIdle()

        viewModel.onEvent(PinEvent.PinChanged("0000"))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(PinError.WRONG_PIN, state.error)
        assertEquals(PinState.MAX_ATTEMPTS - 1, state.attemptsLeft)
        assertEquals("", state.pin.code)
        assertEquals(PinStage.Unlock, state.stage)
    }

    @Test
    fun `a correct pin after a mistake restores the attempt counter`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = viewModel(FakePinStorage(initialPin = "1234"))
        advanceUntilIdle()

        viewModel.onEvent(PinEvent.PinChanged("0000"))
        advanceUntilIdle()
        viewModel.onEvent(PinEvent.PinChanged("1234"))
        advanceUntilIdle()

        assertEquals(PinState.MAX_ATTEMPTS, viewModel.state.value.attemptsLeft)
    }

    @Test
    fun `spent attempts drop the session and send the user back to login`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val storage = FakePinStorage(initialPin = "1234")
        val viewModel = viewModel(storage)
        advanceUntilIdle()

        repeat(PinState.MAX_ATTEMPTS) {
            viewModel.onEvent(PinEvent.PinChanged("0000"))
            advanceUntilIdle()
        }

        val state = viewModel.state.value
        assertEquals(PinError.TOO_MANY_ATTEMPTS, state.error)
        assertEquals(PinStage.Create, state.stage)
        assertEquals("счётчик сброшен для следующего входа", PinState.MAX_ATTEMPTS, state.attemptsLeft)
        // Бесконечный подбор невозможен: PIN и сессия стёрты.
        assertNull(storage.storedPin)
        assertEquals(1, authRepository.logoutCount)
    }

    @Test
    fun `forgotten pin logs out and restarts the authorization`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val storage = FakePinStorage(initialPin = "1234")
        val viewModel = viewModel(storage)
        advanceUntilIdle()

        viewModel.onEvent(PinEvent.ForgotPin)
        val effect = viewModel.effects.first()

        assertEquals(PinEffect.AuthRestartRequired, effect)
        assertNull(storage.storedPin)
        assertEquals(1, authRepository.logoutCount)
    }

    @Test
    fun `a keystore failure on save shows an error instead of crashing`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val storage = FakePinStorage()
        val viewModel = viewModel(storage)
        advanceUntilIdle()

        viewModel.onEvent(PinEvent.PinChanged("123456"))
        advanceUntilIdle()
        storage.failure = GeneralSecurityException("ключ инвалидирован")
        viewModel.onEvent(PinEvent.PinChanged("123456"))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(PinError.STORAGE, state.error)
        assertEquals("установка начинается заново", PinStage.Create, state.stage)
        assertEquals(false, state.busy)
        assertNull(storage.storedPin)
    }

    @Test
    fun `a keystore failure reaches the crash reports`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        // Отказ Keystore пользователю показан текстом, но без отчёта (issue #74)
        // о нём никто не узнает: воспроизводится он только на конкретной прошивке.
        CrashReporting.install(crashReporter)
        val storage = FakePinStorage(initialPin = "1234")
        val viewModel = viewModel(storage)
        advanceUntilIdle()
        storage.failure = GeneralSecurityException("хранилище недоступно")

        viewModel.onEvent(PinEvent.PinChanged("1234"))
        advanceUntilIdle()

        val operations = crashReporter.reports.map { it.operation }
        assertTrue(operations.toString(), operations.contains("pin.verify"))
        assertEquals(PinError.STORAGE, viewModel.state.value.error)
    }

    @Test
    fun `a keystore failure on verify does not spend an attempt`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val storage = FakePinStorage(initialPin = "1234")
        val viewModel = viewModel(storage)
        advanceUntilIdle()
        storage.failure = GeneralSecurityException("хранилище недоступно")

        viewModel.onEvent(PinEvent.PinChanged("1234"))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(PinError.STORAGE, state.error)
        // Пользователь не виноват: лимит попыток сбросил бы сессию на ровном месте.
        assertEquals(PinState.MAX_ATTEMPTS, state.attemptsLeft)
        assertEquals(PinStage.Unlock, state.stage)
        assertEquals(false, state.busy)
    }

    @Test
    fun `forgotten pin restarts the authorization even if clearing fails`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val storage = FakePinStorage(initialPin = "1234")
        val viewModel = viewModel(storage)
        advanceUntilIdle()
        storage.failure = GeneralSecurityException("хранилище недоступно")

        viewModel.onEvent(PinEvent.ForgotPin)
        val effect = viewModel.effects.first()

        assertEquals(PinEffect.AuthRestartRequired, effect)
        assertEquals(false, viewModel.state.value.busy)
    }

    @Test
    fun `an unfinished login without a session restarts the authorization`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        // Процесс умер между вводом кода и этим экраном: испытание было только
        // в памяти, сессии нет. Придуманный сейчас PIN открыл бы приложение,
        // где каждый запрос отвечает 401.
        val viewModel = PinViewModel(FakePinStorage(), FakeAuthRepository())
        val effect = viewModel.effects.first()

        assertEquals(PinEffect.AuthRestartRequired, effect)
    }

    @Test
    fun `a legacy four digit pin keeps a four cell field`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        // PIN, сохранённый до issue #51: шесть ячеек ввести его не дали бы.
        val viewModel = viewModel(FakePinStorage(initialPin = "1234"))
        advanceUntilIdle()

        assertEquals(4, viewModel.state.value.pin.length)
    }

    @Test
    fun `a pending setup sends the pin to the backend before storing it`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        authRepository.pendingServerPin = ServerPinChallenge(ServerPinStep.Setup, "s-1")
        val storage = FakePinStorage()
        val viewModel = viewModel(storage)
        advanceUntilIdle()

        assertEquals(PinStage.Create, viewModel.state.value.stage)
        assertEquals(ServerPin.LENGTH, viewModel.state.value.pin.length)

        viewModel.onEvent(PinEvent.PinChanged("123456"))
        advanceUntilIdle()
        viewModel.onEvent(PinEvent.PinChanged("123456"))
        val effect = viewModel.effects.first()

        assertEquals(PinEffect.PinReady, effect)
        // Тот же код уходит и на сервер (он выдаёт токены), и в Keystore.
        assertEquals(listOf("123456"), authRepository.completedServerPins)
        assertEquals("123456", storage.storedPin)
    }

    @Test
    fun `a pending enter verifies the pin on the server even without a stored one`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        authRepository.pendingServerPin = ServerPinChallenge(ServerPinStep.Enter)
        val storage = FakePinStorage()
        val viewModel = viewModel(storage)
        advanceUntilIdle()

        // Локального хэша нет (новое устройство), а вводить всё равно надо:
        // код проверит бэкенд.
        assertEquals(PinStage.Unlock, viewModel.state.value.stage)

        viewModel.onEvent(PinEvent.PinChanged("654321"))
        val effect = viewModel.effects.first()

        assertEquals(PinEffect.PinReady, effect)
        assertEquals(listOf("654321"), authRepository.completedServerPins)
        // Принятый сервером код становится локальным: следующий запуск
        // разблокируется без сети.
        assertEquals("654321", storage.storedPin)
    }

    @Test
    fun `a rejected pin shows the backend message and stores nothing`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        authRepository.pendingServerPin = ServerPinChallenge(ServerPinStep.Enter)
        authRepository.completeServerPinResult = ApiResult.Failure(
            ApiFailure(
                error = ApiError.Unauthorized,
                server = ServerError(
                    httpCode = 401,
                    code = "PIN_INVALID",
                    message = "Noto'g'ri PIN. 2 ta urinish qoldi.",
                ),
            ),
        )
        val storage = FakePinStorage()
        val viewModel = viewModel(storage)
        advanceUntilIdle()

        viewModel.onEvent(PinEvent.PinChanged("000000"))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("Noto'g'ri PIN. 2 ta urinish qoldi.", state.apiFailure?.serverMessage)
        assertEquals(false, state.busy)
        assertEquals("", state.pin.code)
        assertEquals(PinStage.Unlock, state.stage)
        assertNull("непринятый сервером код локальным не становится", storage.storedPin)
        // Счётчик попыток ведёт сервер: свой стёр бы сессию раньше времени.
        assertEquals(PinState.MAX_ATTEMPTS, state.attemptsLeft)
    }

    @Test
    fun `a rejected setup starts over instead of asking to repeat`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        authRepository.pendingServerPin = ServerPinChallenge(ServerPinStep.Setup, "s-1")
        authRepository.completeServerPinResult = ApiResult.Failure(ApiError.NoConnection)
        val storage = FakePinStorage()
        val viewModel = viewModel(storage)
        advanceUntilIdle()

        viewModel.onEvent(PinEvent.PinChanged("123456"))
        advanceUntilIdle()
        viewModel.onEvent(PinEvent.PinChanged("123456"))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(PinStage.Create, state.stage)
        assertEquals(ApiError.NoConnection, state.apiFailure?.error)
        assertNull(storage.storedPin)
    }

    @Test
    fun `input is ignored while storage is busy`() = runTest(mainDispatcherRule.dispatcher) {
        val storage = FakePinStorage(initialPin = "1234")
        val viewModel = viewModel(storage)
        advanceUntilIdle()

        // Проверка PIN уходит в корутину; вторая пачка цифр до её завершения
        // не должна тратить ещё одну попытку.
        viewModel.onEvent(PinEvent.PinChanged("0000"))
        viewModel.onEvent(PinEvent.PinChanged("0000"))
        advanceUntilIdle()

        assertEquals(PinState.MAX_ATTEMPTS - 1, viewModel.state.value.attemptsLeft)
    }
}
