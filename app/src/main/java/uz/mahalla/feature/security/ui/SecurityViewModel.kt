package uz.mahalla.feature.security.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.isLoading
import uz.mahalla.core.ui.state.toScreenState
import uz.mahalla.core.ui.text.OtpFieldState
import uz.mahalla.data.security.BiometricAvailability
import uz.mahalla.feature.onboarding.data.OnboardingRepository
import uz.mahalla.feature.security.data.SecurityRepository
import uz.mahalla.feature.security.domain.AppLockManager

/**
 * Настройки безопасности (issue #102): смена PIN и переключатель биометрии.
 *
 * Переключатель — не локальный флаг, а запрос `PUT pin/biometric`, и бэкенд
 * требует к нему **PIN**: включение входа по отпечатку это смена настройки
 * безопасности, и подтверждают её кодом. Отсюда шторка с вводом PIN на каждое
 * переключение.
 *
 * Порядок при включении: сначала системный промпт, потом код, потом сервер.
 * Обратный порядок записал бы «биометрия включена» до того, как выяснилось,
 * что датчик не узнаёт хозяина, — и человек остался бы с обещанием, которого
 * приложение не выполнит.
 */
@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val securityRepository: SecurityRepository,
    private val onboardingRepository: OnboardingRepository,
    private val biometricAvailability: BiometricAvailability,
    private val appLockManager: AppLockManager,
) : MviViewModel<SecurityState, SecurityEvent, SecurityEffect>(SecurityState()) {

    /** Код, подтверждённый промптом, но ещё не отправленный: живёт до ответа. */
    private var pendingEnable: Boolean? = null

    init {
        updateState { copy(biometricStatus = biometricAvailability.status()) }
        viewModelScope.launch {
            onboardingRepository.settings.collect { settings ->
                updateState { copy(biometricEnabled = settings.biometricEnabled) }
            }
        }
        load()
        refreshLockState()
    }

    override fun onEvent(event: SecurityEvent) {
        when (event) {
            SecurityEvent.ScreenResumed -> {
                // Отпечаток могли добавить в настройках устройства, а PIN —
                // заблокировать на другом устройстве.
                updateState { copy(biometricStatus = biometricAvailability.status()) }
                refreshLockState()
                if (!currentState.status.isLoading && currentState.pinPrompt == null) {
                    load(showLoading = false)
                }
            }

            SecurityEvent.RetryRequested -> load()

            is SecurityEvent.BiometricToggled -> onToggle(event.enabled)

            // Промпт подтвердил датчик — остался код.
            SecurityEvent.BiometricPromptSucceeded ->
                updateState { copy(pinPrompt = pendingEnable, pin = cleared(), failure = null) }

            SecurityEvent.BiometricPromptFailed -> {
                pendingEnable = null
                updateState { copy(busy = false, biometricPromptFailed = true) }
            }

            SecurityEvent.BiometricPromptCancelled -> {
                pendingEnable = null
                updateState { copy(busy = false) }
            }

            is SecurityEvent.PinChanged -> onPinChanged(event.raw)

            SecurityEvent.PinPromptDismissed -> {
                pendingEnable = null
                updateState { copy(pinPrompt = null, pin = cleared(), busy = false) }
            }
        }
    }

    private fun load(showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) updateState { copy(status = ScreenState.Loading) }
            val loaded = securityRepository.pinStatus().toScreenState()
            updateState { copy(status = loaded) }
        }
    }

    /**
     * Вооружён ли замок. Показывается как состояние, а не как переключатель:
     * выключать app-lock приложение не даёт — это была бы кнопка «сделать мои
     * деньги доступными любому, кто взял телефон».
     */
    private fun refreshLockState() {
        viewModelScope.launch {
            val armed = appLockManager.isArmed()
            updateState { copy(appLockArmed = armed) }
        }
    }

    private fun onToggle(enabled: Boolean) {
        if (!currentState.canToggleBiometric) return
        pendingEnable = enabled
        updateState { copy(failure = null, biometricPromptFailed = false) }
        if (enabled) {
            // Включение начинается с датчика: обещать вход по отпечатку до
            // того, как он сработал хоть раз, нельзя.
            updateState { copy(busy = true) }
            emitEffect(SecurityEffect.ShowBiometricPrompt)
        } else {
            // Выключение датчика не требует: человек как раз говорит, что
            // пользоваться им не будет.
            updateState { copy(pinPrompt = false, pin = cleared()) }
        }
    }

    private fun onPinChanged(raw: String) {
        if (currentState.busy && currentState.pinPrompt == null) return
        updateState { copy(pin = pin.onInput(raw), failure = null) }
        if (currentState.pin.isComplete) submit(currentState.pin.code)
    }

    private fun submit(pin: String) {
        val enabled = currentState.pinPrompt ?: return
        updateState { copy(busy = true) }
        viewModelScope.launch {
            when (val result = securityRepository.setBiometricEnabled(enabled, pin)) {
                is ApiResult.Success -> {
                    pendingEnable = null
                    // Флаг приезжает из репозитория (он же его и записал):
                    // сервер вправе ответить не тем, о чём просили.
                    updateState {
                        copy(
                            busy = false,
                            pinPrompt = null,
                            pin = cleared(),
                            biometricEnabled = result.data,
                        )
                    }
                    load(showLoading = false)
                }

                // Отказ остаётся в шторке рядом с набранным кодом: закрыть её
                // значило бы потерять объяснение (issue #34).
                is ApiResult.Failure -> updateState {
                    copy(busy = false, pin = cleared(), failure = result.failure)
                }
            }
        }
    }

    private fun cleared(): OtpFieldState = currentState.pin.cleared()
}
