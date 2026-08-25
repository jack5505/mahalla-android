package uz.mahalla.feature.onboarding.ui

import dagger.hilt.android.lifecycle.HiltViewModel
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.feature.onboarding.domain.PhoneNumberValidator
import javax.inject.Inject

/**
 * Ввод номера телефона — первый экран с реальной логикой (эпик 1.1).
 * Вся работа синхронная, поэтому ViewModel тестируется на чистом JVM без
 * подмены Main-диспетчера.
 */
@HiltViewModel
class PhoneInputViewModel @Inject constructor(
    private val validator: PhoneNumberValidator,
) : MviViewModel<PhoneInputState, PhoneInputEvent, PhoneInputEffect>(PhoneInputState()) {

    override fun onEvent(event: PhoneInputEvent) {
        when (event) {
            is PhoneInputEvent.PhoneChanged -> onPhoneChanged(event.raw)
            PhoneInputEvent.Submit -> onSubmit()
            PhoneInputEvent.ErrorDismissed -> updateState { copy(error = null) }
        }
    }

    private fun onPhoneChanged(raw: String) {
        val digits = validator.nationalDigits(raw)
        updateState {
            copy(
                nationalDigits = digits,
                formatted = validator.format(digits),
                canSubmit = validator.isValid(digits),
                error = null,
            )
        }
    }

    private fun onSubmit() {
        val digits = currentState.nationalDigits
        if (!validator.isValid(digits)) {
            updateState { copy(error = PhoneInputError.INVALID_NUMBER) }
            return
        }
        emitEffect(PhoneInputEffect.CodeRequested(validator.toE164(digits)))
    }
}
