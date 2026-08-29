package uz.mahalla.feature.onboarding.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.feature.auth.data.AuthRepository
import uz.mahalla.feature.auth.data.TelegramAvailability
import uz.mahalla.feature.onboarding.domain.PhoneNumberValidator
import javax.inject.Inject

/**
 * Ввод номера телефона (3.2): маска и валидация локально, отправка кода —
 * через [AuthRepository].
 *
 * Согласие с офертой проверяется до сети: отправить SMS и потом сказать
 * «отметьте галочку» — потраченный код и потраченные деньги.
 */
@HiltViewModel
class PhoneInputViewModel @Inject constructor(
    private val validator: PhoneNumberValidator,
    private val authRepository: AuthRepository,
    telegramAvailability: TelegramAvailability,
) : MviViewModel<PhoneInputState, PhoneInputEvent, PhoneInputEffect>(
    PhoneInputState(
        telegramAvailable = telegramAvailability.installedPackage() != null,
    ),
) {

    override fun onEvent(event: PhoneInputEvent) {
        when (event) {
            is PhoneInputEvent.PhoneChanged -> onPhoneChanged(event.raw)
            is PhoneInputEvent.ConsentChanged -> updateState {
                copy(
                    consentAccepted = event.accepted,
                    error = error.takeUnless { it == PhoneInputError.CONSENT_REQUIRED },
                )
            }

            PhoneInputEvent.Submit -> onSubmit()
            PhoneInputEvent.TelegramRequested -> onTelegramRequested()
            PhoneInputEvent.OfferRequested -> emitEffect(PhoneInputEffect.OpenOffer)
            PhoneInputEvent.ErrorDismissed -> updateState { copy(error = null, apiFailure = null) }
        }
    }

    private fun onPhoneChanged(raw: String) {
        val digits = validator.nationalDigits(raw)
        updateState {
            copy(
                nationalDigits = digits,
                formatted = validator.format(digits),
                numberValid = validator.isValid(digits),
                error = null,
                apiFailure = null,
            )
        }
    }

    /**
     * Telegram-путь номера не требует — его сообщит боту сам Telegram. А вот
     * оферту принять всё равно нужно: регистрация от канала доставки не
     * зависит, и обойти согласие через бесплатную кнопку нельзя.
     */
    private fun onTelegramRequested() {
        if (currentState.submitting) return
        if (!currentState.consentAccepted) {
            updateState { copy(error = PhoneInputError.CONSENT_REQUIRED) }
            return
        }
        updateState { copy(error = null, apiFailure = null) }
        emitEffect(PhoneInputEffect.OpenTelegram)
    }

    private fun onSubmit() {
        if (currentState.submitting) return
        val digits = currentState.nationalDigits
        if (!validator.isValid(digits)) {
            updateState { copy(error = PhoneInputError.INVALID_NUMBER) }
            return
        }
        if (!currentState.consentAccepted) {
            updateState { copy(error = PhoneInputError.CONSENT_REQUIRED) }
            return
        }

        val phone = validator.toE164(digits)
        updateState { copy(submitting = true, error = null, apiFailure = null) }
        viewModelScope.launch {
            when (val result = authRepository.requestCode(phone)) {
                is ApiResult.Success -> {
                    updateState { copy(submitting = false) }
                    emitEffect(PhoneInputEffect.CodeRequested(phone, result.data))
                }

                is ApiResult.Failure -> updateState {
                    copy(submitting = false, apiFailure = result.failure)
                }
            }
        }
    }
}
