package uz.mahalla.feature.security.ui.lock

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.text.OtpFieldState
import uz.mahalla.data.security.BiometricStatus
import uz.mahalla.feature.security.domain.ChangePinRules

/** Почему код не подошёл. */
enum class AppLockError {
    /** Код не совпал с сохранённым. */
    WRONG_PIN,

    /** Попытки исчерпаны: вход сброшен, дальше только SMS. */
    TOO_MANY_ATTEMPTS,

    /** Хранилище не ответило — попытка не потрачена. */
    STORAGE,

    /** Датчик отказал (не отмена: её показывать нечем). */
    BIOMETRIC_FAILED,
}

/**
 * @param pin поле ввода. Длина берётся из сохранённого PIN, а не из
 * [ChangePinRules.LENGTH]: у человека с четырёхзначным кодом прежней версии
 * шесть ячеек означали бы «ввести нечем» (issue #51).
 * @param attemptsLeft локальный счётчик. Он здесь не дублирует серверный, а
 * заменяет его: экран блокировки проверяет код без сети, и считать попытки
 * серверу нечем. Исход — не «заблокировано навсегда», а выход в SMS-вход.
 * @param biometricEnabled человек включил вход по отпечатку — и локально, и
 * на сервере (`pin/biometric`).
 * @param apiFailure отказ `auth/pin-resume` с текстом сервера (issue #34).
 * Разблокировку он не отменяет, если код подошёл локально: сессия
 * продолжается со следующим успешным запросом.
 */
data class AppLockState(
    val pin: OtpFieldState = OtpFieldState(length = ChangePinRules.LENGTH),
    val attemptsLeft: Int = MAX_ATTEMPTS,
    val busy: Boolean = false,
    val error: AppLockError? = null,
    val apiFailure: ApiFailure? = null,
    val biometricEnabled: Boolean = false,
    val biometricStatus: BiometricStatus = BiometricStatus.Unavailable,
) : UiState {

    /** Показывать ли кнопку «войти по отпечатку». */
    val canUseBiometric: Boolean get() = biometricEnabled && biometricStatus.canEnable

    companion object {
        /** Столько же, сколько на экране входа: расхождение сбивало бы с толку. */
        const val MAX_ATTEMPTS = 5
    }
}

sealed interface AppLockEvent : UiEvent {
    /**
     * Замок показан. Экземпляр ViewModel привязан к Activity и переживает
     * снятие оверлея, поэтому подготовка идёт событием, а не в `init`: на
     * втором запирании подряд `init` уже не выполнится.
     */
    data object Shown : AppLockEvent

    data class PinChanged(val raw: String) : AppLockEvent

    /** Тап по кнопке «отпечаток»: промпт показывает экран. */
    data object BiometricRequested : AppLockEvent
    data object BiometricSucceeded : AppLockEvent
    data object BiometricFailed : AppLockEvent

    /** Отмена промпта — не ошибка, человек решил ввести код руками. */
    data object BiometricCancelled : AppLockEvent

    /**
     * Экран блокировки снова виден. Статус биометрии мог измениться, пока
     * человек ходил в настройки устройства добавлять отпечаток.
     */
    data object ScreenResumed : AppLockEvent

    /** «Забыли PIN»: выход и вход заново по номеру. */
    data object ForgotPin : AppLockEvent
}

sealed interface AppLockEffect : UiEffect {
    /** Показать системный промпт — он живёт только в Activity. */
    data object ShowBiometricPrompt : AppLockEffect

    /**
     * Сессии больше нет: замок снят, но пускать некуда — приложение уходит на
     * вход. Экран блокировки, за которым нет сессии, это тупик.
     */
    data object AuthRestartRequired : AppLockEffect
}
