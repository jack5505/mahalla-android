package uz.mahalla.feature.onboarding.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.ButtonCaption
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaOtpField
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.text.OtpFieldState
import uz.mahalla.feature.auth.domain.OtpFailure

/**
 * Ввод SMS-кода (3.3): ячейки поверх одного скрытого поля, автофокус,
 * таймер повторной отправки и разные тексты ошибок.
 */
@Composable
fun OtpScreen(
    onVerified: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OtpViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is OtpEffect.Verified -> onVerified(effect.isNewUser)
                // Повторная отправка видна по перезапущенному таймеру и
                // очищенному полю — отдельного сообщения экран не требует.
                OtpEffect.CodeResent -> Unit
            }
        }
    }

    OtpContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
private fun OtpContent(
    state: OtpState,
    onEvent: (OtpEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    // Экран открывается ровно для ввода кода — клавиатура должна быть уже здесь.
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    OnboardingStep(
        title = stringResource(R.string.onboarding_otp_title),
        modifier = modifier,
        subtitle = stringResource(R.string.onboarding_otp_sent_to, state.phone),
        onBack = onBack,
        footer = {
            MahallaButton(
                text = stringResource(R.string.onboarding_otp_action),
                onClick = { onEvent(OtpEvent.Submit) },
                state = ButtonState(enabled = state.canSubmit, loading = state.submitting),
            )
            MahallaButton(
                text = stringResource(R.string.onboarding_otp_resend),
                onClick = { onEvent(OtpEvent.Resend) },
                variant = MahallaButtonVariant.Ghost,
                state = ButtonState(enabled = state.canResend, loading = state.resending),
            )
            if (state.resendInSeconds > 0) {
                ButtonCaption(
                    text = stringResource(
                        R.string.onboarding_otp_resend_timer,
                        state.resendInSeconds,
                    ),
                )
            }
        },
    ) {
        MahallaOtpField(
            state = state.code,
            onCodeChange = { onEvent(OtpEvent.CodeChanged(it)) },
            enabled = !state.submitting && !state.inputBlocked,
            errorText = state.failure?.messageOrNull(),
            focusRequester = focusRequester,
        )
        state.apiFailure?.let { OnboardingApiError(it) }
    }
}

/** Сетевую ошибку показываем отдельно — под полем только «про код». */
@Composable
private fun OtpFailure.messageOrNull(): String? = when (this) {
    OtpFailure.InvalidCode -> stringResource(R.string.onboarding_otp_error_invalid)
    OtpFailure.Expired -> stringResource(R.string.onboarding_otp_error_expired)
    OtpFailure.TooManyAttempts -> stringResource(R.string.onboarding_otp_error_attempts)
    OtpFailure.Network -> null
}

@ThemeLanguagePreviews
@Composable
private fun OtpScreenPreview() {
    PreviewSurface {
        OtpContent(
            state = OtpState(
                phone = "+998901234567",
                code = OtpFieldState(code = "123"),
                resendInSeconds = 42,
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
