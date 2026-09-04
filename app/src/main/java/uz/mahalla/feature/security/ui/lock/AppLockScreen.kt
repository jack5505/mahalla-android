package uz.mahalla.feature.security.ui.lock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.ui.biometric.findFragmentActivity
import uz.mahalla.core.ui.biometric.showBiometricPrompt
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaOtpField
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.feature.onboarding.ui.OnboardingApiError
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing

/**
 * Экран блокировки приложения (issue #102).
 *
 * Рисуется **оверлеем поверх всего**, а не маршрутом навигации: замок обязан
 * накрывать любой экран, включая онбординг с его собственным стартовым
 * пунктом, экран обновления (issue #80) и адрес бэкенда (issue #26).
 * Маршрутом он вмешивался бы в back stack и в deep links; оверлей же строго
 * добавочный — навигация под ним остаётся ровно такой, какой была.
 *
 * @param onAuthRestartRequired сессии больше нет: замок снят, но пускать
 * некуда. Приложение уходит на вход.
 */
@Composable
fun AppLockScreen(
    onAuthRestartRequired: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppLockViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val promptTitle = stringResource(R.string.app_lock_prompt_title)
    val promptSubtitle = stringResource(R.string.app_lock_prompt_subtitle)
    val promptNegative = stringResource(R.string.app_lock_prompt_negative)

    // Каждое появление оверлея — заново: перечитать длину PIN, спросить
    // сервер о сессии и предложить отпечаток.
    LaunchedEffect(Unit) { viewModel.onEvent(AppLockEvent.Shown) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(AppLockEvent.ScreenResumed)
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                AppLockEffect.ShowBiometricPrompt -> {
                    val activity = context.findFragmentActivity()
                    if (activity == null) {
                        // Промпт показать нечем (превью, тест) — экран остаётся
                        // на вводе PIN, а не зависает в ожидании.
                        viewModel.onEvent(AppLockEvent.BiometricCancelled)
                    } else {
                        showBiometricPrompt(
                            activity = activity,
                            title = promptTitle,
                            subtitle = promptSubtitle,
                            negativeLabel = promptNegative,
                            onSuccess = { viewModel.onEvent(AppLockEvent.BiometricSucceeded) },
                            onCancelled = { viewModel.onEvent(AppLockEvent.BiometricCancelled) },
                            onFailed = { viewModel.onEvent(AppLockEvent.BiometricFailed) },
                        )
                    }
                }

                AppLockEffect.AuthRestartRequired -> onAuthRestartRequired()
            }
        }
    }

    AppLockContent(state = state, onEvent = viewModel::onEvent, modifier = modifier)
}

@Composable
private fun AppLockContent(
    state: AppLockState,
    onEvent: (AppLockEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = LocalContext.current.findFragmentActivity()
    // «Назад» замок не открывает — иначе он не замок, а заставка: под
    // оверлеем живой экран с чужими деньгами и заказами. Но и глотать нажатие
    // молча нельзя, это читается как сломанная кнопка, поэтому приложение
    // сворачивается — обычное поведение экрана блокировки.
    BackHandler(enabled = true) { activity?.moveTaskToBack(true) }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding(),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.gap),
            ) {
                Text(
                    text = stringResource(R.string.app_lock_title),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.app_lock_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalMahallaColors.current.fgMuted,
                )
                MahallaOtpField(
                    state = state.pin,
                    onCodeChange = { onEvent(AppLockEvent.PinChanged(it)) },
                    enabled = !state.busy,
                    errorText = state.errorText(),
                    masked = true,
                    focusRequester = focusRequester,
                )
                // Отказ `pin-resume` (issue #34). Разблокировку он не отменяет
                // — но объясняет, почему следующий запрос может ответить 401.
                state.apiFailure?.let { OnboardingApiError(failure = it) }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.gutter, vertical = Spacing.gap),
                verticalArrangement = Arrangement.spacedBy(Spacing.item),
            ) {
                if (state.canUseBiometric) {
                    MahallaButton(
                        text = stringResource(R.string.app_lock_biometric),
                        onClick = { onEvent(AppLockEvent.BiometricRequested) },
                        variant = MahallaButtonVariant.Secondary,
                        state = ButtonState(enabled = !state.busy),
                    )
                }
                MahallaButton(
                    text = stringResource(R.string.onboarding_pin_forgot),
                    onClick = { onEvent(AppLockEvent.ForgotPin) },
                    variant = MahallaButtonVariant.Ghost,
                    state = ButtonState(enabled = !state.busy),
                )
            }
        }
    }
}

@Composable
private fun AppLockState.errorText(): String? = when (error) {
    AppLockError.WRONG_PIN -> stringResource(R.string.onboarding_pin_error_wrong, attemptsLeft)
    AppLockError.TOO_MANY_ATTEMPTS -> stringResource(R.string.onboarding_pin_error_attempts)
    AppLockError.STORAGE -> stringResource(R.string.onboarding_pin_error_storage)
    AppLockError.BIOMETRIC_FAILED -> stringResource(R.string.onboarding_biometric_failed)
    null -> null
}

@ThemeLanguagePreviews
@Composable
private fun AppLockScreenPreview() {
    PreviewSurface {
        AppLockContent(
            state = AppLockState(attemptsLeft = 3, error = AppLockError.WRONG_PIN),
            onEvent = {},
        )
    }
}
