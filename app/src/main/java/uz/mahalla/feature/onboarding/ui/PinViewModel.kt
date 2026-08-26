package uz.mahalla.feature.onboarding.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.text.OtpFieldState
import uz.mahalla.data.security.PinStorage
import uz.mahalla.feature.auth.data.AuthRepository
import javax.inject.Inject

/**
 * PIN-код (3.4): установка с повтором либо ввод уже сохранённого.
 *
 * Этап выбирается по факту наличия PIN в хранилище, а не по маршруту:
 * пользователь, который переустановил приложение, увидит установку, а тот,
 * кто вошёл заново на своём устройстве, — ввод.
 *
 * Первый введённый код держится в поле ViewModel, а не в [PinState]: состояние
 * уходит в UI, и код из него не должен быть виден ни превью, ни логам.
 */
@HiltViewModel
class PinViewModel @Inject constructor(
    private val pinStorage: PinStorage,
    private val authRepository: AuthRepository,
) : MviViewModel<PinState, PinEvent, PinEffect>(PinState()) {

    private var firstEntry: String? = null

    init {
        viewModelScope.launch {
            if (pinStorage.isConfigured()) {
                updateState { copy(stage = PinStage.Unlock) }
            }
        }
    }

    override fun onEvent(event: PinEvent) {
        when (event) {
            is PinEvent.PinChanged -> onPinChanged(event.raw)
            PinEvent.ForgotPin -> restartAuth()
            PinEvent.ErrorDismissed -> updateState { copy(error = null) }
        }
    }

    private fun onPinChanged(raw: String) {
        if (currentState.busy) return
        updateState { copy(pin = pin.onInput(raw), error = null) }
        // Код короткий и фиксированной длины — кнопка «дальше» после четвёртой
        // цифры была бы лишним нажатием.
        if (currentState.pin.isComplete) process(currentState.pin.code)
    }

    private fun process(pin: String) {
        when (currentState.stage) {
            PinStage.Create -> {
                firstEntry = pin
                updateState { copy(stage = PinStage.Confirm, pin = pin(), error = null) }
            }

            PinStage.Confirm -> if (pin == firstEntry) {
                savePin(pin)
            } else {
                firstEntry = null
                updateState {
                    copy(stage = PinStage.Create, pin = pin(), error = PinError.MISMATCH)
                }
            }

            PinStage.Unlock -> verifyPin(pin)
        }
    }

    private fun savePin(pin: String) {
        updateState { copy(busy = true) }
        viewModelScope.launch {
            pinStorage.save(pin)
            firstEntry = null
            updateState { copy(busy = false, pin = pin()) }
            emitEffect(PinEffect.PinReady)
        }
    }

    private fun verifyPin(pin: String) {
        updateState { copy(busy = true) }
        viewModelScope.launch {
            if (pinStorage.verify(pin)) {
                updateState { copy(busy = false, pin = pin(), attemptsLeft = PinState.MAX_ATTEMPTS) }
                emitEffect(PinEffect.PinReady)
                return@launch
            }

            val attemptsLeft = currentState.attemptsLeft - 1
            if (attemptsLeft > 0) {
                updateState {
                    copy(
                        busy = false,
                        pin = pin(),
                        attemptsLeft = attemptsLeft,
                        error = PinError.WRONG_PIN,
                    )
                }
                return@launch
            }

            // Попытки исчерпаны: PIN сбрасывается вместе с сессией — иначе
            // подбор продолжался бы бесконечно, просто с перезапуском экрана.
            pinStorage.clear()
            authRepository.logout()
            firstEntry = null
            updateState {
                copy(
                    stage = PinStage.Create,
                    busy = false,
                    pin = pin(),
                    attemptsLeft = PinState.MAX_ATTEMPTS,
                    error = PinError.TOO_MANY_ATTEMPTS,
                )
            }
            emitEffect(PinEffect.AuthRestartRequired)
        }
    }

    private fun restartAuth() {
        updateState { copy(busy = true) }
        viewModelScope.launch {
            // Забытый PIN восстановить нечем: он стирается, вход идёт заново
            // по SMS. Сессию тоже сбрасываем — она защищалась этим PIN'ом.
            pinStorage.clear()
            authRepository.logout()
            firstEntry = null
            updateState {
                copy(stage = PinStage.Create, busy = false, pin = pin(), error = null)
            }
            emitEffect(PinEffect.AuthRestartRequired)
        }
    }

    /** Пустое поле нужной длины: длина приходит из [PinState.PIN_LENGTH]. */
    private fun pin(): OtpFieldState = OtpFieldState(length = PinState.PIN_LENGTH)
}
