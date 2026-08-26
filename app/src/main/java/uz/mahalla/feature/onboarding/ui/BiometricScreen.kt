package uz.mahalla.feature.onboarding.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.data.security.AndroidBiometricAvailability
import uz.mahalla.data.security.BiometricStatus

/**
 * Вход по биометрии (3.5). Системный `BiometricPrompt` живёт в Activity,
 * поэтому его показывает экран, а ViewModel только решает, можно ли его
 * показывать, и что делать с результатом.
 */
@Composable
fun BiometricScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BiometricViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val promptTitle = stringResource(R.string.onboarding_biometric_prompt_title)
    val promptSubtitle = stringResource(R.string.onboarding_biometric_prompt_subtitle)
    val promptNegative = stringResource(R.string.action_cancel)

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                BiometricEffect.ShowPrompt -> {
                    val activity = context.findFragmentActivity()
                    if (activity == null) {
                        // Экран без FragmentActivity (превью, тесты) — промпт
                        // показать нечем, но состояние зависать не должно.
                        viewModel.onEvent(BiometricEvent.PromptFailed)
                    } else {
                        showBiometricPrompt(
                            activity = activity,
                            title = promptTitle,
                            subtitle = promptSubtitle,
                            negativeLabel = promptNegative,
                            onSuccess = { viewModel.onEvent(BiometricEvent.PromptSucceeded) },
                            onCancelled = { viewModel.onEvent(BiometricEvent.PromptCancelled) },
                            onFailed = { viewModel.onEvent(BiometricEvent.PromptFailed) },
                        )
                    }
                }

                BiometricEffect.Finished -> onFinished()
            }
        }
    }

    BiometricContent(
        state = state,
        onEnable = { viewModel.onEvent(BiometricEvent.Enable) },
        onSkip = { viewModel.onEvent(BiometricEvent.Skip) },
        modifier = modifier,
    )
}

@Composable
private fun BiometricContent(
    state: BiometricState,
    onEnable: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OnboardingStep(
        title = stringResource(R.string.onboarding_biometric_title),
        modifier = modifier,
        subtitle = stringResource(R.string.onboarding_biometric_subtitle),
        footer = {
            MahallaButton(
                text = stringResource(R.string.onboarding_biometric_action),
                onClick = onEnable,
                state = ButtonState(enabled = state.canEnable, loading = state.busy),
            )
            MahallaButton(
                text = stringResource(R.string.onboarding_biometric_skip),
                onClick = onSkip,
                variant = MahallaButtonVariant.Ghost,
                state = ButtonState(enabled = !state.busy),
            )
        },
    ) {
        // Причина недоступности важнее самой недоступности: «нет датчика» и
        // «отпечаток не добавлен» ведут к разным действиям пользователя.
        state.status.explanationRes()?.let { OnboardingError(stringResource(it)) }
        if (state.promptFailed) {
            OnboardingError(stringResource(R.string.onboarding_biometric_failed))
        }
    }
}

private fun BiometricStatus.explanationRes(): Int? = when (this) {
    BiometricStatus.Available -> null
    BiometricStatus.NotEnrolled -> R.string.onboarding_biometric_not_enrolled
    BiometricStatus.NoHardware, BiometricStatus.Unavailable ->
        R.string.onboarding_biometric_unavailable
}

/**
 * `BiometricPrompt` умеет работать только с `FragmentActivity` — ради этого
 * `MainActivity` от неё и наследуется. Контекст в Compose может быть обёрнут
 * (тема, локаль), поэтому обёртки разворачиваются.
 */
private fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}

private fun showBiometricPrompt(
    activity: FragmentActivity,
    title: String,
    subtitle: String,
    negativeLabel: String,
    onSuccess: () -> Unit,
    onCancelled: () -> Unit,
    onFailed: () -> Unit,
) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Отмена — не ошибка: пользователь просто закрыл диалог.
                val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                if (cancelled) onCancelled() else onFailed()
            }
            // onAuthenticationFailed — один неудачный отпечаток; диалог
            // остаётся открытым, и вмешиваться в него не нужно.
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeLabel)
            .setAllowedAuthenticators(AndroidBiometricAvailability.AUTHENTICATORS)
            .build(),
    )
}

@ThemeLanguagePreviews
@Composable
private fun BiometricScreenPreview() {
    PreviewSurface {
        BiometricContent(
            state = BiometricState(status = BiometricStatus.NotEnrolled),
            onEnable = {},
            onSkip = {},
        )
    }
}
