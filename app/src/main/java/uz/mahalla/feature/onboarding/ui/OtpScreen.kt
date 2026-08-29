package uz.mahalla.feature.onboarding.ui

import android.content.ActivityNotFoundException
import android.content.Context
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
import uz.mahalla.feature.auth.domain.OtpDeliveryChannel
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
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is OtpEffect.Verified -> onVerified(effect.isNewUser)
                // Повторная отправка видна по перезапущенному таймеру и
                // очищенному полю — отдельного сообщения экран не требует.
                OtpEffect.CodeResent -> Unit
                is OtpEffect.OpenTelegram -> context.openTelegramApp(effect.packageName)
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

/**
 * Открыть само приложение Telegram — код лежит в чате с ботом.
 *
 * Именно launch-intent, а не ссылка на чат: адреса бота `auth/send-otp` не
 * присылает, а угадывать его имя на клиенте нельзя. Список чатов с последним
 * сообщением от бота — ровно то место, куда человеку и надо.
 */
private fun Context.openTelegramApp(packageName: String) {
    val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
    runCatching { startActivity(intent) }
        .onFailure { if (it !is ActivityNotFoundException) throw it }
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
        // «Отправлен на номер» верно только для SMS. Когда бэкенд выбрал
        // Telegram, та же подпись отправляла человека ждать SMS, которого не
        // будет (issue #54).
        subtitle = if (state.isTelegramChannel) {
            stringResource(R.string.onboarding_otp_sent_to_telegram, state.phone)
        } else {
            stringResource(R.string.onboarding_otp_sent_to, state.phone)
        },
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
                    text = pluralStringResource(
                        R.plurals.onboarding_otp_resend_timer,
                        state.resendInSeconds,
                        state.resendInSeconds,
                    ),
                )
            }
        },
    ) {
        if (state.isTelegramChannel) {
            TelegramDeliveryNotice(state = state, onEvent = onEvent)
        }
        MahallaOtpField(
            state = state.code,
            onCodeChange = { onEvent(OtpEvent.CodeChanged(it)) },
            enabled = !state.submitting && !state.inputBlocked,
            errorText = state.fieldError ?: state.failure?.messageOrNull(),
            focusRequester = focusRequester,
        )
        state.apiFailure?.let {
            OnboardingApiError(failure = it, showMessage = state.showApiMessage)
        }
    }
}

/**
 * Объяснение, куда делся код (issue #54).
 *
 * Текст разный не ради красоты: с установленным Telegram человеку остаётся
 * нажать кнопку, а без него — понять, что код придёт в Telegram-аккаунт с этим
 * номером и открыть его надо где-то ещё. Молчание в этом случае оставляет его
 * ждать SMS до истечения кода.
 */
@Composable
private fun TelegramDeliveryNotice(
    state: OtpState,
    onEvent: (OtpEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    OnboardingNotice(
        text = if (state.canOpenTelegram) {
            stringResource(R.string.onboarding_otp_telegram_hint)
        } else {
            stringResource(R.string.onboarding_otp_telegram_hint_no_app, state.phone)
        },
        modifier = modifier,
        action = if (state.canOpenTelegram) {
            {
                MahallaButton(
                    text = stringResource(R.string.onboarding_telegram_open),
                    onClick = { onEvent(OtpEvent.OpenTelegramRequested) },
                    variant = MahallaButtonVariant.Secondary,
                )
            }
        } else {
            null
        },
    )
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

@ThemeLanguagePreviews
@Composable
private fun OtpScreenTelegramPreview() {
    PreviewSurface {
        OtpContent(
            state = OtpState(
                phone = "+998901234567",
                channel = OtpDeliveryChannel.Telegram,
                telegramPackage = "org.telegram.messenger",
                resendInSeconds = 42,
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@ThemeLanguagePreviews
@Composable
private fun OtpScreenTelegramWithoutAppPreview() {
    PreviewSurface {
        OtpContent(
            state = OtpState(
                phone = "+998901234567",
                channel = OtpDeliveryChannel.Telegram,
                resendInSeconds = 42,
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
