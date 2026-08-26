package uz.mahalla.feature.onboarding.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import uz.mahalla.testutil.FakeAuthRepository
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

    private fun viewModel(pinStorage: FakePinStorage) = PinViewModel(pinStorage, authRepository)

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

        viewModel.onEvent(PinEvent.PinChanged("1234"))
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

        viewModel.onEvent(PinEvent.PinChanged("1234"))
        advanceUntilIdle()
        viewModel.onEvent(PinEvent.PinChanged("1234"))
        val effect = viewModel.effects.first()

        assertEquals(PinEffect.PinReady, effect)
        assertEquals("1234", storage.storedPin)
        assertEquals(1, storage.saveCount)
    }

    @Test
    fun `a mismatching repeat starts over with an error`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val storage = FakePinStorage()
        val viewModel = viewModel(storage)
        advanceUntilIdle()

        viewModel.onEvent(PinEvent.PinChanged("1234"))
        advanceUntilIdle()
        viewModel.onEvent(PinEvent.PinChanged("4321"))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(PinStage.Create, state.stage)
        assertEquals(PinError.MISMATCH, state.error)
        assertEquals("", state.pin.code)
        assertNull("ничего не сохранено", storage.storedPin)

        // Первый ввод забыт: повтор старого кода снова уводит на подтверждение.
        viewModel.onEvent(PinEvent.PinChanged("1111"))
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
