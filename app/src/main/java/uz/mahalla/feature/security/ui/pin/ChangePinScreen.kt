package uz.mahalla.feature.security.ui.pin

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
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaOtpField
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.feature.onboarding.ui.OnboardingApiError
import uz.mahalla.feature.onboarding.ui.OnboardingNotice
import uz.mahalla.feature.onboarding.ui.OnboardingStep

/**
 * Смена PIN (issue #102): текущий код → новый → повтор.
 *
 * Каркас общий с шагами онбординга: экран устроен ровно так же, как установка
 * PIN при входе, и расхождение отступов между ними было бы заметно глазом.
 */
@Composable
fun ChangePinScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChangePinViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                ChangePinEffect.Finished -> onBack()
            }
        }
    }

    ChangePinContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
private fun ChangePinContent(
    state: ChangePinState,
    onEvent: (ChangePinEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(state.done) {
        if (!state.done) runCatching { focusRequester.requestFocus() }
    }

    OnboardingStep(
        title = stringResource(if (state.done) R.string.change_pin_done_title else state.stage.titleRes()),
        modifier = modifier,
        subtitle = if (state.done) null else stringResource(R.string.change_pin_subtitle),
        onBack = onBack,
        footer = {
            if (state.done) {
                MahallaButton(text = stringResource(R.string.action_done), onClick = onBack)
            } else if (state.apiFailure != null) {
                // После отказа начинать всегда приходится с текущего кода, но
                // сказать об этом надо явно: экран уже перескочил на первый
                // шаг, и человек может не понять, что именно у него спрашивают.
                MahallaButton(
                    text = stringResource(R.string.change_pin_restart),
                    onClick = { onEvent(ChangePinEvent.Restart) },
                    variant = MahallaButtonVariant.Ghost,
                    state = ButtonState(enabled = !state.busy),
                )
            }
        },
    ) {
        if (state.done) {
            // Успех не уводит с экрана сам: молчаливый возврат читается как
            // «ничего не произошло» (issue #49).
            OnboardingNotice(text = stringResource(R.string.change_pin_done_message))
            return@OnboardingStep
        }

        MahallaOtpField(
            state = state.pin,
            onCodeChange = { onEvent(ChangePinEvent.PinChanged(it)) },
            enabled = !state.busy,
            errorText = state.errorText(),
            masked = true,
            focusRequester = focusRequester,
        )
        state.apiFailure?.let { OnboardingApiError(failure = it) }
    }
}

@Composable
private fun ChangePinState.errorText(): String? = when (error) {
    ChangePinError.MISMATCH -> stringResource(R.string.onboarding_pin_error_mismatch)
    ChangePinError.SAME_AS_CURRENT -> stringResource(R.string.change_pin_error_same)
    null -> null
}

private fun ChangePinStage.titleRes(): Int = when (this) {
    ChangePinStage.Current -> R.string.change_pin_current_title
    ChangePinStage.New -> R.string.change_pin_new_title
    ChangePinStage.Confirm -> R.string.change_pin_confirm_title
}

@ThemeLanguagePreviews
@Composable
private fun ChangePinScreenPreview() {
    PreviewSurface {
        ChangePinContent(
            state = ChangePinState(stage = ChangePinStage.New),
            onEvent = {},
            onBack = {},
        )
    }
}
