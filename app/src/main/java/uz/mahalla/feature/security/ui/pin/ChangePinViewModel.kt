package uz.mahalla.feature.security.ui.pin

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.text.OtpFieldState
import uz.mahalla.feature.security.data.SecurityRepository
import uz.mahalla.feature.security.domain.ChangePinRules

/**
 * Смена PIN из настроек безопасности (issue #102): `PUT pin/change`.
 *
 * Оба кода держатся в полях ViewModel, а не в [ChangePinState]: состояние
 * уходит в UI, и PIN из него не должен быть виден ни превью, ни логам — то же
 * правило, что у `PinViewModel` (эпик 3.4).
 *
 * Счётчик попыток здесь не ведётся вовсе: текущий код проверяет сервер, он же
 * считает попытки и блокирует (issue #51). Свой лимит расходился бы с ним в
 * сообщениях и стирал бы локальный PIN раньше времени.
 */
@HiltViewModel
class ChangePinViewModel @Inject constructor(
    private val securityRepository: SecurityRepository,
) : MviViewModel<ChangePinState, ChangePinEvent, ChangePinEffect>(ChangePinState()) {

    private var currentPin: String? = null
    private var newPin: String? = null

    override fun onEvent(event: ChangePinEvent) {
        when (event) {
            is ChangePinEvent.PinChanged -> onPinChanged(event.raw)
            ChangePinEvent.Restart -> restart()
        }
    }

    private fun onPinChanged(raw: String) {
        if (currentState.busy || currentState.done) return
        updateState { copy(pin = pin.onInput(raw), error = null, apiFailure = null) }
        // Код короткий и фиксированной длины — отдельная кнопка «дальше» была
        // бы лишним нажатием на каждом из трёх шагов.
        if (currentState.pin.isComplete) process(currentState.pin.code)
    }

    private fun process(pin: String) {
        when (currentState.stage) {
            ChangePinStage.Current -> {
                currentPin = pin
                updateState { copy(stage = ChangePinStage.New, pin = cleared()) }
            }

            ChangePinStage.New ->
                // Ловим это до сети: «сменил PIN на тот же самый» — не смена,
                // а человек уверен, что защитился.
                if (ChangePinRules.isSameAsCurrent(currentPin.orEmpty(), pin)) {
                    updateState { copy(pin = cleared(), error = ChangePinError.SAME_AS_CURRENT) }
                } else {
                    newPin = pin
                    updateState { copy(stage = ChangePinStage.Confirm, pin = cleared()) }
                }

            ChangePinStage.Confirm -> if (pin == newPin) {
                submit(current = currentPin.orEmpty(), new = pin)
            } else {
                // Повтор не совпал — новый код набирают заново, а текущий
                // переспрашивать незачем: его уже ввели верно.
                newPin = null
                updateState {
                    copy(
                        stage = ChangePinStage.New,
                        pin = cleared(),
                        error = ChangePinError.MISMATCH,
                    )
                }
            }
        }
    }

    private fun submit(current: String, new: String) {
        updateState { copy(busy = true) }
        viewModelScope.launch {
            when (val result = securityRepository.changePin(currentPin = current, newPin = new)) {
                is ApiResult.Success -> {
                    forgetPins()
                    updateState { copy(busy = false, pin = cleared(), done = true) }
                }

                // Отказ сервера почти всегда про неверный текущий код, но
                // бывает и про блокировку. Что именно — говорит он сам, а
                // экран возвращается к первому шагу: подтверждать нечего.
                is ApiResult.Failure -> {
                    forgetPins()
                    updateState {
                        copy(
                            stage = ChangePinStage.Current,
                            busy = false,
                            pin = cleared(),
                            apiFailure = result.failure,
                        )
                    }
                }
            }
        }
    }

    private fun restart() {
        forgetPins()
        updateState {
            copy(
                stage = ChangePinStage.Current,
                pin = cleared(),
                error = null,
                apiFailure = null,
                busy = false,
            )
        }
    }

    private fun forgetPins() {
        currentPin = null
        newPin = null
    }

    private fun cleared(): OtpFieldState = currentState.pin.cleared()
}
