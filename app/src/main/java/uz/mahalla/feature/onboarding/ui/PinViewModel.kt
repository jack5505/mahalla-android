package uz.mahalla.feature.onboarding.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.runCatchingCancellable
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.text.OtpFieldState
import uz.mahalla.data.security.PinStorage
import uz.mahalla.feature.auth.data.AuthRepository
import uz.mahalla.feature.auth.domain.ServerPin
import uz.mahalla.feature.auth.domain.ServerPinStep
import javax.inject.Inject

/**
 * PIN-код (3.4): установка с повтором либо ввод уже сохранённого.
 *
 * Этап выбирается по факту наличия PIN в хранилище, а не по маршруту:
 * пользователь, который переустановил приложение, увидит установку, а тот,
 * кто вошёл заново на своём устройстве, — ввод.
 *
 * Если вход ещё не завершён (issue #51), этап диктует бэкенд: PIN здесь не
 * только замок устройства, но и то, чем сервер выдаёт токены после SMS-кода.
 * Тогда экран ходит в сеть, а счётчик попыток ведёт сервер.
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
            // Незавершённый вход важнее локального состояния: пока бэкенд не
            // получит PIN, токенов нет, и «разблокировать» нечего (issue #51).
            val serverStep = authRepository.pendingServerPin?.step
            if (serverStep != null) {
                updateState {
                    copy(
                        serverStep = serverStep,
                        stage = when (serverStep) {
                            ServerPinStep.Setup -> PinStage.Create
                            ServerPinStep.Enter -> PinStage.Unlock
                        },
                        pin = OtpFieldState(length = ServerPin.LENGTH),
                    )
                }
                return@launch
            }

            // Хранилище недоступно — считаем, что PIN не настроен: установка
            // сработает и перезапишет его, а падение на старте экрана нет.
            val savedLength = runCatchingCancellable { pinStorage.configuredLength() }
                .getOrNull()
            if (savedLength != null) {
                updateState {
                    copy(stage = PinStage.Unlock, pin = OtpFieldState(length = savedLength))
                }
                return@launch
            }

            // Ни сессии, ни PIN, ни незавершённого входа — так бывает, когда
            // процесс умер между вводом кода и этим экраном: испытание живёт
            // только в памяти. Придуманный сейчас PIN открыл бы приложение,
            // где каждый запрос отвечает 401, поэтому вход начинается заново.
            if (!authRepository.isAuthorized.first()) {
                emitEffect(PinEffect.AuthRestartRequired)
            }
        }
    }

    override fun onEvent(event: PinEvent) {
        when (event) {
            is PinEvent.PinChanged -> onPinChanged(event.raw)
            PinEvent.ForgotPin -> restartAuth()
            PinEvent.ErrorDismissed -> updateState { copy(error = null, apiFailure = null) }
        }
    }

    private fun onPinChanged(raw: String) {
        if (currentState.busy) return
        updateState { copy(pin = pin.onInput(raw), error = null, apiFailure = null) }
        // Код короткий и фиксированной длины — кнопка «дальше» после последней
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
            // Сначала сервер: пока он не принял PIN, сессии нет, и локальный
            // код защищал бы вход, которого не случилось (issue #51).
            if (currentState.serverStep != null && !completeServerPin(pin)) return@launch

            // Keystore умеет отказать (ключ инвалидирован сменой блокировки
            // экрана, хранилище недоступно) — это ошибка шага, а не крэш.
            if (runCatchingCancellable { pinStorage.save(pin) }.isFailure) {
                firstEntry = null
                updateState {
                    copy(
                        stage = PinStage.Create,
                        busy = false,
                        pin = pin(),
                        error = PinError.STORAGE,
                    )
                }
                return@launch
            }
            firstEntry = null
            updateState { copy(busy = false, pin = pin()) }
            emitEffect(PinEffect.PinReady)
        }
    }

    /**
     * Отправка PIN бэкенду. `true` — токены получены и сохранены; `false` —
     * отказ уже показан на экране, шаг закончен.
     *
     * Ошибку показываем текстом сервера (issue #34): только он знает, сколько
     * попыток осталось и не заблокирован ли аккаунт.
     */
    private suspend fun completeServerPin(pin: String): Boolean =
        when (val result = authRepository.completeServerPin(pin)) {
            is ApiResult.Success -> true

            is ApiResult.Failure -> {
                firstEntry = null
                updateState {
                    copy(
                        // Установка начинается заново: подтверждать нечего,
                        // код бэкенд не принял.
                        stage = if (serverStep == ServerPinStep.Setup) {
                            PinStage.Create
                        } else {
                            stage
                        },
                        busy = false,
                        pin = pin(),
                        apiFailure = result.failure,
                    )
                }
                false
            }
        }

    private fun verifyPin(pin: String) {
        updateState { copy(busy = true) }
        viewModelScope.launch {
            // Сохранённого хэша может не быть вовсе (новое устройство): при
            // `ENTER_PIN` код проверяет сервер, он же выдаёт токены.
            if (currentState.serverStep == ServerPinStep.Enter) {
                if (!completeServerPin(pin)) return@launch
                runCatchingCancellable { pinStorage.save(pin) }
                updateState { copy(busy = false, pin = pin()) }
                emitEffect(PinEffect.PinReady)
                return@launch
            }

            val matches = runCatchingCancellable { pinStorage.verify(pin) }.getOrElse {
                // Хранилище не ответило — попытку не тратим: пользователь не
                // виноват, а лимит привёл бы к сбросу сессии на ровном месте.
                updateState { copy(busy = false, pin = pin(), error = PinError.STORAGE) }
                return@launch
            }

            if (matches) {
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
            // Даже если сброс упал, экран уходит на повторный вход: там же
            // сессия будет перезаписана.
            clearCredentials()
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
            clearCredentials()
            firstEntry = null
            updateState {
                copy(
                    stage = PinStage.Create,
                    busy = false,
                    pin = pin(),
                    error = null,
                    apiFailure = null,
                    // Незавершённый вход выброшен вместе с сессией.
                    serverStep = null,
                )
            }
            emitEffect(PinEffect.AuthRestartRequired)
        }
    }

    /**
     * Сброс PIN и сессии. Обе операции пишут (Keystore и DataStore) и обе
     * могут упасть — уронить приложение на выходе из аккаунта нельзя, а
     * дальнейший сценарий один и тот же: вход заново по SMS.
     */
    private suspend fun clearCredentials() {
        runCatchingCancellable { pinStorage.clear() }
        runCatchingCancellable { authRepository.logout() }
    }

    /**
     * Пустое поле той же длины, что и текущее: она зависит от шага
     * (шесть цифр требует бэкенд) и от того, каким PIN был сохранён раньше.
     */
    private fun pin(): OtpFieldState = currentState.pin.cleared()
}
