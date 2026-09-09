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
import uz.mahalla.data.security.PinAttemptStore
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
 *
 * **Лимит попыток здесь локальный и персистентный** (`PinAttemptStore`). Свой
 * счётчик запрещён в *серверном* режиме — там считает бэкенд (issue #51), — но
 * код на этом экране проверяется без сети, и считать серверу нечем. В памяти
 * счётчик обходился снятием приложения из недавних, поэтому он в DataStore,
 * как предписывает ADR 0004. Исход исчерпанных попыток — не «заблокировано
 * навсегда», а выход в SMS-вход.
 */
@HiltViewModel
class AppLockViewModel @Inject constructor(
    private val pinStorage: PinStorage,
    private val pinAttemptStore: PinAttemptStore,
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
        // Счётчик читается из DataStore на каждом появлении экрана: попытки,
        // потраченные до перезапуска приложения, обязаны остаться потраченными.
        // Отказ хранилища здесь читается как «попыток полный набор», а не как
        // «ни одной»: цена ошибки в другую сторону — человек, запертый вне
        // приложения из-за недоступного DataStore.
        val used = runCatchingCancellable { pinAttemptStore.failedAttempts() }
            .reportSwallowed("applock.failedAttempts")
            .getOrDefault(0)

        updateState {
            copy(
                pin = savedLength?.let { OtpFieldState(length = it) } ?: pin,
                biometricEnabled = biometricEnabled,
                attemptsLeft = AppLockState.MAX_ATTEMPTS - used,
            )
        }

        // Счётчик уже на пределе — значит прошлый раз приложение сняли между
        // последней попыткой и выходом. Досчитывать нечего: показать хотя бы
        // одну попытку значило бы отдавать по попытке за каждое снятие
        // приложения, и лимит перестал бы существовать.
        if (currentState.attemptsLeft <= 0) {
            updateState { copy(error = AppLockError.TOO_MANY_ATTEMPTS) }
            restartAuth()
            return
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
            // Счётчик неверных кодов при этом обнуляется: датчик подтвердил
            // хозяина, и держать за ним прошлые опечатки незачем.
            AppLockEvent.BiometricSucceeded -> {
                appLockManager.unlock()
                updateState { copy(attemptsLeft = AppLockState.MAX_ATTEMPTS) }
                viewModelScope.launch { resetAttempts() }
            }

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
                resetAttempts()
                updateState {
                    copy(busy = false, pin = cleared(), attemptsLeft = AppLockState.MAX_ATTEMPTS)
                }
                resumeServerSession(pin)
                return@launch
            }

            // Записываем в DataStore, а не считаем в состоянии: перезапуск
            // приложения не должен возвращать попытки. Отказ записи не отменяет
            // саму попытку — счётчик тогда идёт от значения в памяти, иначе
            // недоступный DataStore давал бы бесконечный подбор.
            val used = runCatchingCancellable { pinAttemptStore.recordFailure() }
                .reportSwallowed("applock.recordFailure")
                .getOrNull()
            val attemptsLeft = used
                ?.let { AppLockState.MAX_ATTEMPTS - it }
                ?: (currentState.attemptsLeft - 1)
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
     * Обнулить счётчик неверных попыток: замок открыли законно (код, отпечаток)
     * либо вход всё равно сбрасывается и следующий начнётся с чистого листа.
     *
     * Отказ хранилища проглатывается: он оставит счётчик как есть, и человек
     * недосчитается попыток в следующий раз — это заметно, но обратимо
     * (SMS-вход), в отличие от падения на успешной разблокировке.
     */
    private suspend fun resetAttempts() {
        runCatchingCancellable { pinAttemptStore.reset() }.reportSwallowed("applock.resetAttempts")
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
     * **Замок здесь не снимается.** Снять его — значит убрать оверлей, а
     * вместе с ним и коллектор эффектов: `AuthRestartRequired` ушёл бы в
     * буфер канала, из которого его больше никто не забирает, и человек
     * остался бы на том экране, где его застал фон, — с выключенной сессией и
     * 401 на каждый запрос. Поэтому оверлей стоит до конца, а разбирает замок
     * тот, кто получил эффект (`RootViewModel.onAuthRestartRequired`).
     *
     * Счётчик попыток обнуляется здесь же: дальше человек проходит SMS-вход, а
     * унесённые в него потраченные попытки заперли бы уже новую сессию.
     */
    private fun restartAuth() {
        updateState { copy(busy = true) }
        viewModelScope.launch {
            runCatchingCancellable { authRepository.logout() }.reportSwallowed("applock.logout")
            runCatchingCancellable { onboardingRepository.clearCompleted() }
                .reportSwallowed("applock.clearOnboardingCompleted")
            resetAttempts()
            updateState { copy(busy = false, pin = cleared()) }
            emitEffect(AppLockEffect.AuthRestartRequired)
        }
    }

    private fun cleared(): OtpFieldState = currentState.pin.cleared()
}
