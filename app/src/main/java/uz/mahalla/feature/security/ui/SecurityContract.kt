package uz.mahalla.feature.security.ui

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.text.OtpFieldState
import uz.mahalla.data.security.BiometricStatus
import uz.mahalla.feature.security.domain.ChangePinRules
import uz.mahalla.feature.security.domain.ServerPinStatus

/**
 * Настройки безопасности (issue #102).
 *
 * @param status что о PIN знает бэкенд. Отказ не прячет экран целиком: смена
 * PIN и переключатель всё равно ходят в сеть и объяснят себя сами.
 * @param biometricEnabled локальный флаг — по нему экран блокировки решает,
 * показывать ли промпт. Источник истины серверный, но читать его на каждом
 * запуске незачем.
 * @param appLockArmed замок вооружён: есть сессия и локальная копия PIN. Это
 * не настройка, а состояние — показываем его, потому что редкий отказ
 * Keystore при смене PIN замок разоружает, и молчать об этом нельзя.
 * @param pinPrompt открыта шторка подтверждения PIN: `true` — включаем
 * биометрию, `false` — выключаем. Код требует сам бэкенд (`pin/biometric`).
 * @param failure отказ переключателя текстом сервера (issue #34).
 */
data class SecurityState(
    val status: ScreenState<ServerPinStatus> = ScreenState.Loading,
    val biometricEnabled: Boolean = false,
    val biometricStatus: BiometricStatus = BiometricStatus.Unavailable,
    val appLockArmed: Boolean = false,
    val pinPrompt: Boolean? = null,
    val pin: OtpFieldState = OtpFieldState(length = ChangePinRules.LENGTH),
    val busy: Boolean = false,
    val failure: ApiFailure? = null,
    /** Датчик отказал: включение не состоялось, и молчать об этом нельзя. */
    val biometricPromptFailed: Boolean = false,
) : UiState {

    /**
     * Переключать биометрию можно, только когда устройство её умеет: иначе
     * человек включил бы вход, которым не сможет воспользоваться. Выключить
     * при этом можно всегда — флаг мог остаться от устройства, где датчик
     * работал.
     */
    val canToggleBiometric: Boolean
        get() = !busy && (biometricEnabled || biometricStatus.canEnable)
}

sealed interface SecurityEvent : UiEvent {
    /** Возврат на экран: статус PIN и доступность датчика могли измениться. */
    data object ScreenResumed : SecurityEvent
    data object RetryRequested : SecurityEvent

    data class BiometricToggled(val enabled: Boolean) : SecurityEvent

    /** Промпт подтвердил, что датчик работает и человек на месте. */
    data object BiometricPromptSucceeded : SecurityEvent
    data object BiometricPromptFailed : SecurityEvent
    data object BiometricPromptCancelled : SecurityEvent

    data class PinChanged(val raw: String) : SecurityEvent
    data object PinPromptDismissed : SecurityEvent
}

sealed interface SecurityEffect : UiEffect {
    /** Показать системный промпт — он живёт только в Activity. */
    data object ShowBiometricPrompt : SecurityEffect
}
