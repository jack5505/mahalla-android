package uz.mahalla.feature.onboarding.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.text.OtpFieldState
import uz.mahalla.feature.auth.data.AuthRepository
import uz.mahalla.feature.auth.domain.OtpChallenge
import uz.mahalla.feature.auth.domain.OtpFailure
import uz.mahalla.feature.auth.domain.asOtpFailure
import uz.mahalla.navigation.OtpArgs
import javax.inject.Inject

/**
 * Ввод SMS-кода (3.3).
 *
 * Код проверяется автоматически на последней цифре: отдельная кнопка
 * «Подтвердить» после набора шестой цифры — лишний шаг, ради которого
 * пользователь ищет её пальцем под клавиатурой. Кнопка при этом остаётся —
 * для повторной отправки после ошибки.
 *
 * Таймер повтора отсчитывается корутиной в `viewModelScope`, поэтому в
 * тестах он проходит по виртуальному времени, а не по настоящим секундам.
 */
@HiltViewModel
class OtpViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val authRepository: AuthRepository,
) : MviViewModel<OtpState, OtpEvent, OtpEffect>(
    OtpState(
        phone = savedStateHandle[OtpArgs.PHONE] ?: "",
        otpToken = savedStateHandle[OtpArgs.OTP_TOKEN] ?: "",
        code = OtpFieldState(
            length = savedStateHandle[OtpArgs.CODE_LENGTH] ?: OtpChallenge.DEFAULT_CODE_LENGTH,
        ),
        resendInSeconds = savedStateHandle[OtpArgs.RESEND_AFTER_SECONDS]
            ?: OtpChallenge.DEFAULT_RESEND_SECONDS,
    ),
) {

    private var countdownJob: Job? = null

    init {
        startCountdown()
    }

    override fun onEvent(event: OtpEvent) {
        when (event) {
            is OtpEvent.CodeChanged -> onCodeChanged(event.raw)
            OtpEvent.Submit -> submit()
            OtpEvent.Resend -> resend()
            OtpEvent.ErrorDismissed -> updateState { copy(failure = null, apiFailure = null) }
        }
    }

    private fun onCodeChanged(raw: String) {
        if (currentState.inputBlocked || currentState.submitting) return
        updateState { copy(code = code.onInput(raw), failure = null, apiFailure = null) }
        if (currentState.code.isComplete) submit()
    }

    private fun submit() {
        val state = currentState
        if (!state.canSubmit) return

        updateState { copy(submitting = true, failure = null, apiFailure = null) }
        viewModelScope.launch {
            when (val result = authRepository.verifyCode(state.otpToken, state.code.code)) {
                is ApiResult.Success -> {
                    updateState { copy(submitting = false) }
                    emitEffect(OtpEffect.Verified(result.data.isNewUser))
                }

                is ApiResult.Failure -> {
                    val failure = result.failure.asOtpFailure()
                    updateState {
                        copy(
                            submitting = false,
                            failure = failure,
                            // Отдельным блоком показываем сетевую ошибку и
                            // любой ответ, где бэкенд назвал причину словами
                            // (issue #34): «включите геолокацию» вместо
                            // подписи поля «код неверный». Остальное уже
                            // сказано под полем — дублировать нечего.
                            apiFailure = result.failure.takeIf {
                                failure == OtpFailure.Network || it.serverMessage != null
                            },
                            // Сеть — не про код: введённые цифры не трогаем,
                            // пользователь просто повторяет отправку.
                            code = if (failure == OtpFailure.Network) code else code.cleared().asError(),
                        )
                    }
                }
            }
        }
    }

    private fun resend() {
        if (!currentState.canResend) return

        updateState { copy(resending = true, apiFailure = null) }
        viewModelScope.launch {
            when (val result = authRepository.requestCode(currentState.phone)) {
                is ApiResult.Success -> {
                    updateState {
                        copy(
                            resending = false,
                            failure = null,
                            apiFailure = null,
                            // Новый код — новый токен: старый бэкенд уже
                            // погасил, и проверять по нему нечего.
                            otpToken = result.data.otpToken,
                            code = OtpFieldState(length = result.data.codeLength),
                            resendInSeconds = result.data.resendAfterSeconds,
                        )
                    }
                    startCountdown()
                    emitEffect(OtpEffect.CodeResent)
                }

                is ApiResult.Failure -> updateState {
                    copy(resending = false, apiFailure = result.failure)
                }
            }
        }
    }

    /** Один активный отсчёт: повторная отправка перезапускает его, а не добавляет второй. */
    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            while (currentState.resendInSeconds > 0) {
                delay(SECOND_MILLIS)
                updateState { copy(resendInSeconds = (resendInSeconds - 1).coerceAtLeast(0)) }
            }
        }
    }

    private companion object {
        const val SECOND_MILLIS = 1_000L
    }
}
