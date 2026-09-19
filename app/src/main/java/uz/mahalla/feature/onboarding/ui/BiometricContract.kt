package uz.mahalla.feature.onboarding.ui

import androidx.biometric.BiometricPrompt
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

    /**
     * Экран снова на переднем плане. Пользователь мог уйти в настройки
     * устройства и добавить отпечаток — статус надо перечитать, иначе кнопка
     * «Включить» останется выключенной навсегда: ViewModel переживает уход в
     * настройки вместе с записью в back stack.
     */
    data object ScreenResumed : BiometricEvent

    /** Промпт подтвердил датчик — `cryptoObject` прогоняется через шифрование (issue #318). */
    data class PromptSucceeded(val cryptoObject: BiometricPrompt.CryptoObject) : BiometricEvent
    data object PromptFailed : BiometricEvent

    /** Отмена самого промпта — не ошибка, просто ничего не произошло. */
    data object PromptCancelled : BiometricEvent
    data object Skip : BiometricEvent
}

sealed interface BiometricEffect : UiEffect {
    /** Показать системный BiometricPrompt — он живёт только в Activity. */
    data class ShowPrompt(val cryptoObject: BiometricPrompt.CryptoObject) : BiometricEffect

    /** Шаг пройден (включили или пропустили) — дальше геолокация. */
    data object Finished : BiometricEffect
}
