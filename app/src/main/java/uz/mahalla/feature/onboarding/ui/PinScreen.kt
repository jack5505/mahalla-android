package uz.mahalla.feature.onboarding.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaPinDots
import uz.mahalla.core.ui.components.MahallaPinPad
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews

/**
 * PIN-код (3.4): установка с повтором либо ввод сохранённого.
 *
 * Ячейки маскированы — код не должен читаться с экрана через плечо; проверка
 * идёт по последней цифре, отдельной кнопки подтверждения нет.
 */
@Composable
fun PinScreen(
    onPinReady: () -> Unit,
    onAuthRestartRequired: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PinViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                PinEffect.PinReady -> onPinReady()
                PinEffect.AuthRestartRequired -> onAuthRestartRequired()
            }
        }
    }

    PinContent(state = state, onEvent = viewModel::onEvent, modifier = modifier)
}

@Composable
private fun PinContent(
    state: PinState,
    onEvent: (PinEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    OnboardingStep(
        title = stringResource(state.stage.titleRes()),
        modifier = modifier,
        subtitle = stringResource(R.string.onboarding_pin_subtitle),
        // На разблокировке счётчика нет: это не шаг регистрации, а вход в уже
        // заведённое приложение — «Шаг 3 из 5» там означал бы, что впереди
        // ещё два.
        stepLabel = OnboardingStepNumber.Pin.label().takeIf { state.stage != PinStage.Unlock },
        footer = {
            if (state.stage == PinStage.Unlock) {
                MahallaButton(
                    text = stringResource(R.string.onboarding_pin_forgot),
                    onClick = { onEvent(PinEvent.ForgotPin) },
                    variant = MahallaButtonVariant.Ghost,
                    state = ButtonState(enabled = !state.busy),
                )
            }
        },
    ) {
        MahallaPinDots(state = state.pin, isError = state.error != null)
        state.errorText()?.let { OnboardingError(it) }
        MahallaPinPad(
            onDigit = { digit -> onEvent(PinEvent.DigitPressed(digit)) },
            onBackspace = { onEvent(PinEvent.BackspacePressed) },
            enabled = !state.busy,
        )
        // Отказ бэкенда на PIN-шаге входа (issue #51): текст сервера точнее
        // собственного, а подробности ответа нужны для поддержки.
        state.apiFailure?.let { OnboardingApiError(failure = it) }
    }
}

@Composable
private fun PinState.errorText(): String? = when (error) {
    PinError.MISMATCH -> stringResource(R.string.onboarding_pin_error_mismatch)
    PinError.WRONG_PIN -> stringResource(R.string.onboarding_pin_error_wrong, attemptsLeft)
    PinError.TOO_MANY_ATTEMPTS -> stringResource(R.string.onboarding_pin_error_attempts)
    PinError.STORAGE -> stringResource(R.string.onboarding_pin_error_storage)
    PinError.FOREIGN_ACCOUNT -> stringResource(R.string.onboarding_error_foreign_account)
    null -> null
}

private fun PinStage.titleRes(): Int = when (this) {
    PinStage.Create -> R.string.onboarding_pin_title
    PinStage.Confirm -> R.string.onboarding_pin_confirm_title
    PinStage.Unlock -> R.string.onboarding_pin_unlock_title
}

@ThemeLanguagePreviews
@Composable
private fun PinScreenPreview() {
    PreviewSurface {
        PinContent(
            state = PinState(stage = PinStage.Confirm, error = PinError.MISMATCH),
            onEvent = {},
        )
    }
}
