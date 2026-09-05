package uz.mahalla.feature.security.ui.pin

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.text.OtpFieldState
import uz.mahalla.feature.security.domain.ChangePinRules

/** Три кода подряд: текущий, новый и его повтор. */
enum class ChangePinStage {
    Current,
    New,
    Confirm,
}

enum class ChangePinError {
    /** Повтор не совпал с новым кодом. */
    MISMATCH,

    /** Новый код равен текущему: смены не произошло бы. */
    SAME_AS_CURRENT,
}

/**
 * @param stage какой из трёх кодов вводят сейчас.
 * @param apiFailure отказ `pin/change` текстом сервера (issue #34): только он
 * знает, сколько попыток осталось и не заблокирован ли PIN.
 * @param done PIN сменён. Экран не уходит сам — молчаливый возврат читается
 * как «ничего не произошло» (этому научил issue #49).
 */
data class ChangePinState(
    val stage: ChangePinStage = ChangePinStage.Current,
    val pin: OtpFieldState = OtpFieldState(length = ChangePinRules.LENGTH),
    val busy: Boolean = false,
    val error: ChangePinError? = null,
    val apiFailure: ApiFailure? = null,
    val done: Boolean = false,
) : UiState

sealed interface ChangePinEvent : UiEvent {
    data class PinChanged(val raw: String) : ChangePinEvent

    /** «Начать заново»: после отказа сервера текущий код мог быть набран неверно. */
    data object Restart : ChangePinEvent
}

sealed interface ChangePinEffect : UiEffect {
    /** Пользователь закрыл экран после успеха. */
    data object Finished : ChangePinEffect
}
