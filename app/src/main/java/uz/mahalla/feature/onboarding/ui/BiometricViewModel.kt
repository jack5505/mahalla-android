package uz.mahalla.feature.onboarding.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.data.security.BiometricAvailability
import uz.mahalla.feature.onboarding.data.OnboardingRepository
import javax.inject.Inject

/**
 * Вход по биометрии (3.5).
 *
 * Флаг включается только после успешного промпта: иначе пользователь,
 * закрывший системный диалог, получил бы «биометрия включена» и запрос
 * отпечатка на следующем запуске без единого подтверждения.
 *
 * Пропуск — тоже осознанный ответ, поэтому он явно пишет `false`: PIN уже
 * настроен и остаётся фолбэком (3.4).
 */
@HiltViewModel
class BiometricViewModel @Inject constructor(
    private val onboardingRepository: OnboardingRepository,
    biometricAvailability: BiometricAvailability,
) : MviViewModel<BiometricState, BiometricEvent, BiometricEffect>(
    BiometricState(status = biometricAvailability.status()),
) {

    override fun onEvent(event: BiometricEvent) {
        when (event) {
            BiometricEvent.Enable -> {
                if (!currentState.canEnable) return
                updateState { copy(busy = true, promptFailed = false) }
                emitEffect(BiometricEffect.ShowPrompt)
            }

            BiometricEvent.PromptSucceeded -> setEnabled(true)
            BiometricEvent.PromptFailed -> updateState { copy(busy = false, promptFailed = true) }
            BiometricEvent.PromptCancelled -> updateState { copy(busy = false) }
            BiometricEvent.Skip -> setEnabled(false)
        }
    }

    private fun setEnabled(enabled: Boolean) {
        updateState { copy(busy = true, promptFailed = false) }
        viewModelScope.launch {
            onboardingRepository.setBiometricEnabled(enabled)
            updateState { copy(busy = false) }
            emitEffect(BiometricEffect.Finished)
        }
    }
}
