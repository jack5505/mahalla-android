package uz.mahalla.feature.security.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.biometric.findFragmentActivity
import uz.mahalla.core.ui.biometric.showBiometricPrompt
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaBottomSheet
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaErrorDetails
import uz.mahalla.core.ui.components.MahallaListItem
import uz.mahalla.core.ui.components.MahallaOtpField
import uz.mahalla.core.ui.components.MahallaSwitchRow
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.SectionHeader
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.dataOrNull
import uz.mahalla.core.ui.userMessage
import uz.mahalla.data.security.BiometricStatus
import uz.mahalla.feature.security.domain.ServerPinStatus
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing

/**
 * Безопасность (issue #102): смена PIN, вход по биометрии и состояние замка.
 *
 * Отдельный экран, а не строки в профиле: PIN и биометрия — это одна тема с
 * общим правилом («подтверди кодом»), и разложенные по списку настроек они
 * читались бы как несвязанные переключатели.
 */
@Composable
fun SecurityScreen(
    onBack: () -> Unit,
    onChangePin: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SecurityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val promptTitle = stringResource(R.string.security_biometric_prompt_title)
    val promptSubtitle = stringResource(R.string.security_biometric_prompt_subtitle)
    val promptNegative = stringResource(R.string.action_cancel)

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(SecurityEvent.ScreenResumed)
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                SecurityEffect.ShowBiometricPrompt -> {
                    val activity = context.findFragmentActivity()
                    if (activity == null) {
                        viewModel.onEvent(SecurityEvent.BiometricPromptCancelled)
                    } else {
                        showBiometricPrompt(
                            activity = activity,
                            title = promptTitle,
                            subtitle = promptSubtitle,
                            negativeLabel = promptNegative,
                            onSuccess = {
                                viewModel.onEvent(SecurityEvent.BiometricPromptSucceeded)
                            },
                            onCancelled = {
                                viewModel.onEvent(SecurityEvent.BiometricPromptCancelled)
                            },
                            onFailed = { viewModel.onEvent(SecurityEvent.BiometricPromptFailed) },
                        )
                    }
                }
            }
        }
    }

    SecurityContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        onChangePin = onChangePin,
        modifier = modifier,
    )
}

@Composable
private fun SecurityContent(
    state: SecurityState,
    onEvent: (SecurityEvent) -> Unit,
    onBack: () -> Unit,
    onChangePin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(title = stringResource(R.string.security_title), onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.gap),
            verticalArrangement = Arrangement.spacedBy(Spacing.gap),
        ) {
            AppLockCard(armed = state.appLockArmed)

            SectionHeader(title = stringResource(R.string.security_pin_section))
            MahallaCard {
                MahallaListItem(
                    title = stringResource(R.string.security_change_pin),
                    subtitle = state.status.pinSubtitle(),
                    onClick = onChangePin,
                )
                MahallaSwitchRow(
                    title = stringResource(R.string.security_biometric),
                    checked = state.biometricEnabled,
                    onCheckedChange = { onEvent(SecurityEvent.BiometricToggled(it)) },
                    description = state.biometricDescription(),
                    enabled = state.canToggleBiometric,
                )
            }

            // Блокировку сервер снимает сам по времени — показываем её как
            // причину, по которой запросы отвечают отказом.
            state.status.dataOrNull()?.takeIf { it.locked }?.let { status ->
                SecurityNote(
                    text = pluralStringResource(
                        R.plurals.security_locked,
                        status.lockedSecondsRemaining.toInt(),
                        status.lockedSecondsRemaining,
                    ),
                )
            }

            // Отказ статуса экран не прячет: смена PIN и переключатель всё
            // равно ходят в сеть и объяснят себя сами. Но кнопка повтора
            // нужна — иначе строка «PIN-код установлен» не появится до
            // следующего возврата на экран.
            (state.status as? ScreenState.Error)?.let { screen ->
                SecurityFailure(
                    failure = screen.failure,
                    modifier = Modifier.padding(horizontal = Spacing.gutter),
                    onRetry = { onEvent(SecurityEvent.RetryRequested) },
                )
            }
        }
    }

    if (state.pinPrompt != null) {
        BiometricPinSheet(state = state, onEvent = onEvent)
    }
}

/**
 * Состояние замка, а не переключатель: выключать app-lock приложение не даёт.
 * Показать его всё равно надо — редкий отказ Keystore при смене PIN замок
 * разоружает, и человек должен об этом узнать не от вора.
 */
@Composable
private fun AppLockCard(armed: Boolean) {
    MahallaCard(modifier = Modifier.padding(horizontal = Spacing.gutter)) {
        Column(
            modifier = Modifier.padding(Spacing.card),
            verticalArrangement = Arrangement.spacedBy(Spacing.item),
        ) {
            MahallaBadge(
                text = stringResource(
                    if (armed) R.string.security_lock_on else R.string.security_lock_off,
                ),
                tone = if (armed) MahallaTone.Success else MahallaTone.Warning,
            )
            Text(
                text = stringResource(
                    if (armed) {
                        R.string.security_lock_on_description
                    } else {
                        R.string.security_lock_off_description
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMahallaColors.current.fgMuted,
            )
        }
    }
}

/**
 * Подтверждение PIN для переключателя биометрии: код требует сам бэкенд
 * (`pin/biometric`). Отказ остаётся здесь же, рядом с набранным кодом.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BiometricPinSheet(state: SecurityState, onEvent: (SecurityEvent) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    MahallaBottomSheet(
        onDismiss = { onEvent(SecurityEvent.PinPromptDismissed) },
        title = stringResource(R.string.security_confirm_pin_title),
    ) {
        Text(
            text = stringResource(
                if (state.pinPrompt == true) {
                    R.string.security_confirm_pin_enable
                } else {
                    R.string.security_confirm_pin_disable
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalMahallaColors.current.fgMuted,
        )
        MahallaOtpField(
            state = state.pin,
            onCodeChange = { onEvent(SecurityEvent.PinChanged(it)) },
            enabled = !state.busy,
            masked = true,
            focusRequester = focusRequester,
        )
        state.failure?.let { SecurityFailure(failure = it) }
    }
}

/**
 * @param onRetry `null` там, где повторять нечего: отказ переключателя
 * повторяется вводом кода заново, и кнопка «повторить» отправила бы тот же
 * PIN второй раз.
 */
@Composable
private fun SecurityFailure(
    failure: ApiFailure,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        Text(
            text = failure.userMessage(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        failure.server?.let { MahallaErrorDetails(server = it) }
        if (onRetry != null) {
            MahallaButton(
                text = stringResource(R.string.action_retry),
                onClick = onRetry,
                variant = MahallaButtonVariant.Secondary,
            )
        }
    }
}

@Composable
private fun SecurityNote(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = Spacing.gutter),
        style = MaterialTheme.typography.bodyMedium,
        color = LocalMahallaColors.current.fgMuted,
    )
}

/**
 * Подпись строки смены PIN. Пока статус не приехал — молчим: «PIN не
 * установлен» на месте загрузки читалось бы как факт.
 */
@Composable
private fun ScreenState<ServerPinStatus>.pinSubtitle(): String? = when (this) {
    is ScreenState.Content ->
        stringResource(
            if (data.pinSet) R.string.security_pin_set else R.string.security_pin_not_set,
        )

    else -> null
}

@Composable
private fun SecurityState.biometricDescription(): String? = when {
    biometricPromptFailed -> stringResource(R.string.onboarding_biometric_failed)
    biometricStatus == BiometricStatus.NotEnrolled && !biometricEnabled ->
        stringResource(R.string.onboarding_biometric_not_enrolled)

    !biometricStatus.canEnable && !biometricEnabled ->
        stringResource(R.string.onboarding_biometric_unavailable)

    else -> stringResource(R.string.security_biometric_description)
}

@ThemeLanguagePreviews
@Composable
private fun SecurityScreenPreview() {
    PreviewSurface {
        SecurityContent(
            state = SecurityState(
                status = ScreenState.Content(
                    ServerPinStatus(
                        pinSet = true,
                        biometricEnabled = true,
                        lockedSecondsRemaining = 0,
                    ),
                ),
                biometricEnabled = true,
                biometricStatus = BiometricStatus.Available,
                appLockArmed = true,
            ),
            onEvent = {},
            onBack = {},
            onChangePin = {},
        )
    }
}
