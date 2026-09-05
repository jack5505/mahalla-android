package uz.mahalla.feature.security.ui.lock

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import uz.mahalla.core.crash.reportSwallowed
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.runCatchingCancellable
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.text.OtpFieldState
import uz.mahalla.data.security.BiometricAvailability
import uz.mahalla.data.security.PinStorage
import uz.mahalla.feature.onboarding.data.OnboardingRepository
import uz.mahalla.feature.auth.data.AuthRepository
import uz.mahalla.feature.security.data.SecurityRepository
import uz.mahalla.feature.security.domain.AppLockManager

/**
 * Экран блокировки (issue #102): PIN или отпечаток при возврате из фона.
 *
 * **Код проверяется локально, а серверу сообщается вдогонку.** Причина в
 * несимметричной цене ошибки: экран блокировки, которому нужна сеть, — это
 * кирпич в метро и в самолёте, а локальная копия PIN не расходится с сервером
 * по построению (её пишет только код, который бэкенд принял, см.
 * `SecurityRepository`). Поэтому `auth/pin-resume` вызывается **после**
 * успешной локальной проверки и его отказ разблокировку не отменяет: он
 * продлевает серверную сессию, а не решает, пускать ли человека в его же
 * приложение.
 *
 * Единственный исход, при котором экран не помогает, — мёртвая сессия. Про неё
 * говорит `auth/session/check`, и тогда приложение уходит на вход: ждать PIN
 * от того, у кого сессии нет, значит запереть его насовсем.
 */
@HiltViewModel
class AppLockViewModel @Inject constructor(
    private val pinStorage: PinStorage,
    private val onboardingRepository: OnboardingRepository,
    private val biometricAvailability: BiometricAvailability,
    private val securityRepository: SecurityRepository,
    private val appLockManager: AppLockManager,
    private val authRepository: AuthRepository,
) : MviViewModel<AppLockState, AppLockEvent, AppLockEffect>(AppLockState()) {

    /**
     * Подготовка идёт по событию [AppLockEvent.Shown], а **не** в `init`.
     *
     * ViewModel этого экрана привязана к Activity, а не к записи навигации:
     * оверлей живёт вне графа. Значит на втором запирании подряд она — тот же
     * самый экземпляр, и всё, что стояло бы в `init`, во второй раз просто не
     * случилось бы: ни промпта, ни проверки сессии.
     */
    private fun onShown() {
        updateState {
            copy(
                biometricStatus = biometricAvailability.status(),
                // Прошлое запирание могло закончиться ошибкой — показывать её
                // поверх нового замка незачем.
                error = null,
                apiFailure = null,
                busy = false,
                pin = pin.cleared(),
            )
        }
        viewModelScope.launch { prepare() }
        viewModelScope.launch { verifySessionAlive() }
    }

    /**
     * Длина поля и доступность отпечатка. Промпт показывается сам: человек
     * включил вход по биометрии как раз чтобы не набирать код.
     */
    private suspend fun prepare() {
        val savedLength = runCatchingCancellable { pinStorage.configuredLength() }
            .reportSwallowed("applock.configuredLength")
            .getOrNull()
        val biometricEnabled = runCatchingCancellable {
            onboardingRepository.settings.first().biometricEnabled
        }
            .reportSwallowed("applock.biometricEnabled")
            .getOrDefault(false)

        updateState {
            copy(
                pin = savedLength?.let { OtpFieldState(length = it) } ?: pin,
                biometricEnabled = biometricEnabled,
            )
        }
        if (currentState.canUseBiometric) emitEffect(AppLockEffect.ShowBiometricPrompt)
    }

    /**
     * Жива ли сессия. Отказ сети сюда не доезжает намеренно: «спросить не
     * удалось» — не «сессии нет», и выкидывать человека из аккаунта из-за
     * пропавшего интернета нельзя. Мёртвый токен приложение всё равно узнает
     * по первому же 401 после разблокировки.
     */
    private suspend fun verifySessionAlive() {
        val result = securityRepository.checkSession()
        if (result is ApiResult.Success && !result.data.valid) restartAuth()
    }

    override fun onEvent(event: AppLockEvent) {
        when (event) {
            AppLockEvent.Shown -> onShown()

            is AppLockEvent.PinChanged -> onPinChanged(event.raw)

            AppLockEvent.BiometricRequested ->
                if (currentState.canUseBiometric && !currentState.busy) {
                    emitEffect(AppLockEffect.ShowBiometricPrompt)
                }

            // Отпечаток — полноценная замена PIN'у: так решил сам человек,
            // включив его. Серверу об этом сообщить нечем — ручки «продолжить
            // сессию по биометрии» у бэкенда нет, есть только `pin-resume`.
            AppLockEvent.BiometricSucceeded -> appLockManager.unlock()

            AppLockEvent.BiometricFailed ->
                updateState { copy(error = AppLockError.BIOMETRIC_FAILED) }

            AppLockEvent.BiometricCancelled -> Unit

            AppLockEvent.ScreenResumed ->
                updateState { copy(biometricStatus = biometricAvailability.status()) }

            AppLockEvent.ForgotPin -> restartAuth()
        }
    }

    private fun onPinChanged(raw: String) {
        if (currentState.busy) return
        updateState { copy(pin = pin.onInput(raw), error = null, apiFailure = null) }
        if (currentState.pin.isComplete) verify(currentState.pin.code)
    }

    private fun verify(pin: String) {
        updateState { copy(busy = true) }
        viewModelScope.launch {
            val matches = runCatchingCancellable { pinStorage.verify(pin) }
                .reportSwallowed("applock.verify")
                .getOrElse {
                    // Хранилище не ответило — попытку не тратим: человек не
                    // виноват, а лимит стоил бы ему входа на ровном месте.
                    updateState { copy(busy = false, pin = cleared(), error = AppLockError.STORAGE) }
                    return@launch
                }

            if (matches) {
                // Замок снимается сразу: сеть здесь не участвует, и ждать её
                // значило бы держать человека перед экраном лишние секунды.
                appLockManager.unlock()
                updateState {
                    copy(busy = false, pin = cleared(), attemptsLeft = AppLockState.MAX_ATTEMPTS)
                }
                resumeServerSession(pin)
                return@launch
            }

            val attemptsLeft = currentState.attemptsLeft - 1
            if (attemptsLeft > 0) {
                updateState {
                    copy(
                        busy = false,
                        pin = cleared(),
                        attemptsLeft = attemptsLeft,
                        error = AppLockError.WRONG_PIN,
                    )
                }
                return@launch
            }

            // Попытки исчерпаны. Это не «заперли навсегда»: вход сбрасывается,
            // и человек заходит по SMS — иначе подбор продолжался бы
            // бесконечно, просто с перезапуском приложения.
            updateState {
                copy(
                    busy = false,
                    pin = cleared(),
                    attemptsLeft = AppLockState.MAX_ATTEMPTS,
                    error = AppLockError.TOO_MANY_ATTEMPTS,
                )
            }
            restartAuth()
        }
    }

    /**
     * Сказать серверу, что сессия продолжена. Результат ни на что не влияет:
     * замок уже снят локальной проверкой того же кода. Отказ показывается
     * текстом сервера — он может объяснить, почему следующий запрос ответит
     * 401, — но экран к этому моменту уже закрыт, и увидит его только тот, кто
     * запёрся снова.
     */
    private suspend fun resumeServerSession(pin: String) {
        val result = securityRepository.resumeSession(pin)
        if (result is ApiResult.Failure) updateState { copy(apiFailure = result.failure) }
    }

    /**
     * Сессии нет или PIN не восстановить: выходим и уводим на вход.
     *
     * Замок снимается **до** выхода: иначе оверлей остался бы поверх экрана
     * входа, где вводить нечего.
     */
    private fun restartAuth() {
        updateState { copy(busy = true) }
        viewModelScope.launch {
            appLockManager.disarm()
            runCatchingCancellable { authRepository.logout() }.reportSwallowed("applock.logout")
            runCatchingCancellable { onboardingRepository.clearCompleted() }
                .reportSwallowed("applock.clearOnboardingCompleted")
            updateState { copy(busy = false, pin = cleared()) }
            emitEffect(AppLockEffect.AuthRestartRequired)
        }
    }

    private fun cleared(): OtpFieldState = currentState.pin.cleared()
}
