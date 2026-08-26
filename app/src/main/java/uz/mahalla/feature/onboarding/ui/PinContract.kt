package uz.mahalla.feature.onboarding.ui

import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.text.OtpFieldState

/** Этап работы с PIN (3.4). */
enum class PinStage {
    /** Придумать код. */
    Create,

    /** Повторить придуманный код. */
    Confirm,

    /** Ввести уже сохранённый код — вход на устройстве, где PIN настроен. */
    Unlock,
}

enum class PinError {
    /** Повтор не совпал с первым вводом. */
    MISMATCH,

    /** Сохранённый PIN не подошёл. */
    WRONG_PIN,

    /** Попытки исчерпаны: PIN сброшен, нужен вход заново. */
    TOO_MANY_ATTEMPTS,
}

data class PinState(
    val stage: PinStage = PinStage.Create,
    val pin: OtpFieldState = OtpFieldState(length = PIN_LENGTH),
    /** Осталось попыток на этапе [PinStage.Unlock]. */
    val attemptsLeft: Int = MAX_ATTEMPTS,
    val busy: Boolean = false,
    val error: PinError? = null,
) : UiState {
    companion object {
        const val PIN_LENGTH = 4

        /** Пять попыток, затем PIN сбрасывается и требуется вход по SMS. */
        const val MAX_ATTEMPTS = 5
    }
}

sealed interface PinEvent : UiEvent {
    data class PinChanged(val raw: String) : PinEvent

    /** «Забыли PIN» — выход и вход заново по номеру телефона. */
    data object ForgotPin : PinEvent
    data object ErrorDismissed : PinEvent
}

sealed interface PinEffect : UiEffect {
    /** PIN установлен или подтверждён — дальше биометрия. */
    data object PinReady : PinEffect

    /** Сессия сброшена: PIN не восстановить, нужен вход по SMS с начала. */
    data object AuthRestartRequired : PinEffect
}
