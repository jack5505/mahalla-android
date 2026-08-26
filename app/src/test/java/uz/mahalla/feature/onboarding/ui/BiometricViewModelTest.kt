package uz.mahalla.feature.onboarding.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.data.security.BiometricAvailability
import uz.mahalla.data.security.BiometricStatus
import uz.mahalla.testutil.FakeOnboardingRepository
import uz.mahalla.testutil.MainDispatcherRule
import java.io.IOException

/**
 * Биометрия (3.5): флаг включается только после успешного промпта, пропуск —
 * тоже осознанный ответ.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BiometricViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val onboardingRepository = FakeOnboardingRepository()

    private fun viewModel(status: BiometricStatus) =
        BiometricViewModel(onboardingRepository, FakeBiometricAvailability(status))

    @Test
    fun `availability is read at start`() {
        assertTrue(viewModel(BiometricStatus.Available).state.value.canEnable)
        assertFalse(viewModel(BiometricStatus.NotEnrolled).state.value.canEnable)
        assertFalse(viewModel(BiometricStatus.NoHardware).state.value.canEnable)
        assertFalse(viewModel(BiometricStatus.Unavailable).state.value.canEnable)
    }

    @Test
    fun `returning to the screen re-reads availability`() {
        // Сценарий: «отпечаток не добавлен» → пользователь уходит в настройки
        // устройства, добавляет его и возвращается. ViewModel та же (запись в
        // back stack жива), и статус обязан перечитаться — иначе кнопка
        // «Включить» останется выключенной навсегда.
        val availability = FakeBiometricAvailability(BiometricStatus.NotEnrolled)
        val viewModel = BiometricViewModel(onboardingRepository, availability)
        assertFalse(viewModel.state.value.canEnable)

        availability.status = BiometricStatus.Available
        viewModel.onEvent(BiometricEvent.ScreenResumed)

        assertTrue(viewModel.state.value.canEnable)
    }

    @Test
    fun `a datastore failure does not crash the step`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        onboardingRepository.writeFailure = IOException("нет места")
        val viewModel = viewModel(BiometricStatus.Available)

        viewModel.onEvent(BiometricEvent.Skip)
        val effect = viewModel.effects.first()

        // Шаг заканчивается: без флага биометрия просто выключена, а PIN уже
        // настроен и остаётся входом.
        assertEquals(BiometricEffect.Finished, effect)
        assertFalse(viewModel.state.value.busy)
    }

    @Test
    fun `enable asks for the system prompt without writing the flag`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = viewModel(BiometricStatus.Available)

        viewModel.onEvent(BiometricEvent.Enable)

        assertEquals(BiometricEffect.ShowPrompt, viewModel.effects.first())
        // Ключевое: до подтверждения биометрия не считается включённой.
        assertTrue(onboardingRepository.biometricWrites.isEmpty())
    }

    @Test
    fun `enable does nothing when biometrics are unavailable`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = viewModel(BiometricStatus.NoHardware)

        viewModel.onEvent(BiometricEvent.Enable)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.busy)
        assertTrue(onboardingRepository.biometricWrites.isEmpty())
    }

    @Test
    fun `a successful prompt turns the flag on and finishes the step`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = viewModel(BiometricStatus.Available)

        viewModel.onEvent(BiometricEvent.PromptSucceeded)
        advanceUntilIdle()

        assertEquals(listOf(true), onboardingRepository.biometricWrites)
        assertTrue(onboardingRepository.current.biometricEnabled)
    }

    @Test
    fun `a failed prompt keeps the user on the screen`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = viewModel(BiometricStatus.Available)
        viewModel.onEvent(BiometricEvent.Enable)

        viewModel.onEvent(BiometricEvent.PromptFailed)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.promptFailed)
        assertFalse(viewModel.state.value.busy)
        assertTrue(onboardingRepository.biometricWrites.isEmpty())
    }

    @Test
    fun `a cancelled prompt is not an error`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel(BiometricStatus.Available)
        viewModel.onEvent(BiometricEvent.Enable)

        viewModel.onEvent(BiometricEvent.PromptCancelled)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.promptFailed)
        assertFalse(viewModel.state.value.busy)
        assertTrue(viewModel.state.value.canEnable)
    }

    @Test
    fun `skip records the choice explicitly and finishes the step`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = viewModel(BiometricStatus.Available)

        viewModel.onEvent(BiometricEvent.Skip)
        val effect = viewModel.effects.first()

        assertEquals(BiometricEffect.Finished, effect)
        assertEquals(listOf(false), onboardingRepository.biometricWrites)
    }

    private class FakeBiometricAvailability(
        var status: BiometricStatus,
    ) : BiometricAvailability {
        override fun status(): BiometricStatus = status
    }
}
