package uz.mahalla.feature.onboarding.ui

import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.data.security.BiometricStatus

data class BiometricState(
    val status: BiometricStatus = BiometricStatus.Unavailable,
    val busy: Boolean = false,
    /** Промпт не прошёл: показываем подсказку, но с экрана не уводим. */
    val promptFailed: Boolean = false,
) : UiState {
    val canEnable: Boolean get() = status.canEnable && !busy
}

sealed interface BiometricEvent : UiEvent {
    data object Enable : BiometricEvent
    data object PromptSucceeded : BiometricEvent
    data object PromptFailed : BiometricEvent

    /** Отмена самого промпта — не ошибка, просто ничего не произошло. */
    data object PromptCancelled : BiometricEvent
    data object Skip : BiometricEvent
}

sealed interface BiometricEffect : UiEffect {
    /** Показать системный BiometricPrompt — он живёт только в Activity. */
    data object ShowPrompt : BiometricEffect

    /** Шаг пройден (включили или пропустили) — дальше геолокация. */
    data object Finished : BiometricEffect
}
