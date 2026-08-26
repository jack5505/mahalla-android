package uz.mahalla.feature.onboarding.ui

import uz.mahalla.core.result.ApiError
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.text.OtpFieldState
import uz.mahalla.feature.auth.domain.OtpFailure

data class OtpState(
    val phone: String = "",
    val code: OtpFieldState = OtpFieldState(),
    val submitting: Boolean = false,
    /** Сколько секунд осталось до разблокировки «Отправить снова». */
    val resendInSeconds: Int = 0,
    val resending: Boolean = false,
    val failure: OtpFailure? = null,
    /** Сетевая ошибка запроса нового кода — показывается отдельно от [failure]. */
    val apiError: ApiError? = null,
) : UiState {
    /**
     * Лимит попыток исчерпан или код истёк: вводить дальше бессмысленно,
     * пока не придёт новый код.
     */
    val inputBlocked: Boolean
        get() = failure == OtpFailure.TooManyAttempts || failure == OtpFailure.Expired

    val canResend: Boolean get() = resendInSeconds == 0 && !resending && !submitting

    val canSubmit: Boolean get() = code.isComplete && !submitting && !inputBlocked
}

sealed interface OtpEvent : UiEvent {
    data class CodeChanged(val raw: String) : OtpEvent
    data object Submit : OtpEvent
    data object Resend : OtpEvent
    data object ErrorDismissed : OtpEvent
}

sealed interface OtpEffect : UiEffect {
    /** Код принят, сессия сохранена — дальше PIN. */
    data class Verified(val isNewUser: Boolean) : OtpEffect

    /** Новый код ушёл — сообщаем пользователю снекбаром. */
    data object CodeResent : OtpEffect
}
