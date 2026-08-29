package uz.mahalla.feature.onboarding.ui

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.feature.auth.domain.OtpChallenge

enum class PhoneInputError {
    INVALID_NUMBER,

    /** Оферта не отмечена — код не отправляем (3.2). */
    CONSENT_REQUIRED,
}

data class PhoneInputState(
    /** Только цифры национальной части — источник истины для валидации. */
    val nationalDigits: String = "",
    /** То, что видит пользователь: `+998 90 123 45 67`. */
    val formatted: String = "+998",
    /** Номер прошёл валидацию (проверяет ViewModel, состояние только хранит). */
    val numberValid: Boolean = false,
    val consentAccepted: Boolean = false,
    val submitting: Boolean = false,
    /**
     * На устройстве есть Telegram — значит доступен бесплатный вход (issue
     * #46). Нет — кнопки нет вовсе: ссылка на бота открылась бы в браузере,
     * где нажать Start невозможно.
     */
    val telegramAvailable: Boolean = false,
    val error: PhoneInputError? = null,
    /**
     * Ошибка запроса кода: сеть, лимит, 5xx, отказ бэкенда. Показывается
     * текстом сервера, если он его прислал, иначе общим (issue #34).
     */
    val apiFailure: ApiFailure? = null,
) : UiState {
    /**
     * Кнопка активна только когда запрос действительно можно отправить:
     * неполный номер и неотмеченная оферта её не разблокируют.
     */
    val canSubmit: Boolean get() = numberValid && consentAccepted && !submitting
}

sealed interface PhoneInputEvent : UiEvent {
    data class PhoneChanged(val raw: String) : PhoneInputEvent
    data class ConsentChanged(val accepted: Boolean) : PhoneInputEvent
    data object Submit : PhoneInputEvent
    data object OfferRequested : PhoneInputEvent

    /** Войти через Telegram-бот вместо SMS (issue #46). */
    data object TelegramRequested : PhoneInputEvent
    data object ErrorDismissed : PhoneInputEvent
}

sealed interface PhoneInputEffect : UiEffect {
    /** Код отправлен — можно открывать экран ввода OTP. */
    data class CodeRequested(
        val phoneE164: String,
        val challenge: OtpChallenge,
    ) : PhoneInputEffect

    /** Открыть текст оферты во внешнем браузере. */
    data object OpenOffer : PhoneInputEffect

    /** Перейти на бесплатный вход через Telegram-бот (issue #46). */
    data object OpenTelegram : PhoneInputEffect
}
