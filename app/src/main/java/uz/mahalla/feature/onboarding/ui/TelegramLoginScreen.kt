package uz.mahalla.feature.onboarding.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing

/**
 * Вход через Telegram-бот (issue #46) — бесплатная замена SMS.
 *
 * Экран сам открывает чат с ботом и ждёт, пока человек нажмёт Start. Кода
 * вводить не нужно: подтверждением служит сам факт нажатия в аккаунте с
 * известным номером.
 */
@Composable
fun TelegramLoginScreen(
    onConfirmed: (Boolean) -> Unit,
    onSmsRequested: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TelegramLoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Возвращение на экран почти всегда означает нажатый Start — проверяем
    // сразу, не дожидаясь очередной паузы опроса.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(TelegramEvent.ScreenResumed)
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is TelegramEffect.OpenBot -> context.openTelegram(effect)
                is TelegramEffect.Confirmed -> onConfirmed(effect.isNewUser)
                TelegramEffect.SwitchToSms -> onSmsRequested()
            }
        }
    }

    TelegramLoginContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * Открыть чат с ботом в самом Telegram.
 *
 * Пакет проставляется намеренно: ссылку `https://t.me/…` умеет открывать любой
 * браузер, а нажать Start можно только в приложении — и одноразовый токен
 * входа не должен уезжать никуда, кроме Telegram. Если адресат не подошёл
 * (клиент успели удалить, пакет отключён), пробуем без него: тогда систему
 * попросит выбрать пользователь.
 */
private fun Context.openTelegram(effect: TelegramEffect.OpenBot) {
    val uri = Uri.parse(effect.url)
    val targeted = Intent(Intent.ACTION_VIEW, uri).apply {
        effect.packageName?.let(::setPackage)
    }
    if (start(targeted)) return
    if (effect.packageName != null) start(Intent(Intent.ACTION_VIEW, uri))
}

private fun Context.start(intent: Intent): Boolean = runCatching {
    startActivity(intent)
}.onFailure { if (it !is ActivityNotFoundException) throw it }.isSuccess

@Composable
private fun TelegramLoginContent(
    state: TelegramState,
    onEvent: (TelegramEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OnboardingStep(
        title = stringResource(R.string.onboarding_telegram_title),
        modifier = modifier,
        subtitle = stringResource(state.subtitleRes),
        onBack = onBack,
        footer = {
            when {
                // Бэкенд просит подтвердить номер кодом — других шагов на этом
                // экране не осталось, и SMS-кнопка становится главной.
                state.needsPhoneVerify -> MahallaButton(
                    text = stringResource(R.string.onboarding_telegram_verify_phone),
                    onClick = { onEvent(TelegramEvent.SmsRequested) },
                )

                state.canRetry -> {
                    MahallaButton(
                        text = stringResource(R.string.onboarding_telegram_retry),
                        onClick = { onEvent(TelegramEvent.RetryRequested) },
                    )
                    TelegramSmsFallbackButton(onEvent)
                }

                else -> {
                    MahallaButton(
                        text = stringResource(R.string.onboarding_telegram_open),
                        onClick = { onEvent(TelegramEvent.OpenBotRequested) },
                        state = ButtonState(
                            enabled = state.canOpenBot,
                            loading = state.status == TelegramStatus.PREPARING,
                        ),
                    )
                    TelegramSmsFallbackButton(onEvent)
                }
            }
        },
    ) {
        if (state.isWaiting) {
            TelegramWaitingRow()
        }
        // Номер называем явно: человек только что отдал боту контакт и должен
        // понимать, какой именно номер предстоит подтвердить.
        state.phone?.let { phone ->
            Text(
                text = stringResource(R.string.onboarding_telegram_phone_verify_number, phone),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        state.apiFailure?.let { OnboardingApiError(it) }
    }
}

/**
 * Путь наружу есть всегда: Telegram может быть установлен, но не тем аккаунтом,
 * и запирать человека в бесплатном канале нельзя.
 */
@Composable
private fun TelegramSmsFallbackButton(onEvent: (TelegramEvent) -> Unit) {
    MahallaButton(
        text = stringResource(R.string.onboarding_telegram_use_sms),
        onClick = { onEvent(TelegramEvent.SmsRequested) },
        variant = MahallaButtonVariant.Ghost,
    )
}

/** Индикатор ожидания Start — с текстом, иначе крутилка ничего не объясняет. */
@Composable
private fun TelegramWaitingRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(Spacing.item),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
        )
        Text(
            text = stringResource(R.string.onboarding_telegram_waiting),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalMahallaColors.current.fgMuted,
        )
    }
}

private val TelegramState.subtitleRes: Int
    get() = when (status) {
        TelegramStatus.PREPARING -> R.string.onboarding_telegram_subtitle
        TelegramStatus.WAITING -> R.string.onboarding_telegram_subtitle
        TelegramStatus.CONFIRMED -> R.string.onboarding_telegram_confirmed
        TelegramStatus.PHONE_VERIFY -> R.string.onboarding_telegram_phone_verify
        TelegramStatus.EXPIRED -> R.string.onboarding_telegram_expired
        TelegramStatus.FAILED -> R.string.onboarding_telegram_failed
    }

@ThemeLanguagePreviews
@Composable
private fun TelegramLoginScreenPreview() {
    PreviewSurface {
        TelegramLoginContent(
            state = TelegramState(
                status = TelegramStatus.WAITING,
                botUrl = "https://t.me/MahallaVerifyBot?start=abc",
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@ThemeLanguagePreviews
@Composable
private fun TelegramLoginPhoneVerifyPreview() {
    PreviewSurface {
        TelegramLoginContent(
            state = TelegramState(
                status = TelegramStatus.PHONE_VERIFY,
                phone = "+998901234567",
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@ThemeLanguagePreviews
@Composable
private fun TelegramLoginExpiredPreview() {
    PreviewSurface {
        TelegramLoginContent(
            state = TelegramState(status = TelegramStatus.EXPIRED),
            onEvent = {},
            onBack = {},
        )
    }
}
