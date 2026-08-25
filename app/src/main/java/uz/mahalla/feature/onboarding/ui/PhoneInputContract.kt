package uz.mahalla.feature.onboarding.ui

import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState

enum class PhoneInputError {
    INVALID_NUMBER,
}

data class PhoneInputState(
    /** Только цифры национальной части — источник истины для валидации. */
    val nationalDigits: String = "",
    /** То, что видит пользователь: `+998 90 123 45 67`. */
    val formatted: String = "+998",
    val canSubmit: Boolean = false,
    val error: PhoneInputError? = null,
) : UiState

sealed interface PhoneInputEvent : UiEvent {
    data class PhoneChanged(val raw: String) : PhoneInputEvent
    data object Submit : PhoneInputEvent
    data object ErrorDismissed : PhoneInputEvent
}

sealed interface PhoneInputEffect : UiEffect {
    /** Номер принят — можно запрашивать SMS-код (эпик 2). */
    data class CodeRequested(val phoneE164: String) : PhoneInputEffect
}
