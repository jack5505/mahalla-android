package uz.mahalla.feature.update.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.feature.onboarding.ui.OnboardingError
import uz.mahalla.feature.onboarding.ui.OnboardingNotice
import uz.mahalla.feature.onboarding.ui.OnboardingStep
import uz.mahalla.feature.update.domain.AppUpdate
import uz.mahalla.ui.theme.LocalMahallaColors

/**
 * Экран обновления приложения (issue #80, задача T12).
 *
 * Показывается один раз при запуске, до всего остального: контракт бэкенда
 * ломался четыре раза за месяц, и старая сборка переставала работать молча —
 * человек видел «Nimadir xato ketdi» и не понимал, что надо обновиться.
 *
 * Два режима на одном экране: обязательное обновление (кнопка одна, уйти
 * некуда) и мягкое предложение с «Позже». Разделять их на два экрана незачем —
 * различаются они ровно одной кнопкой и текстом заголовка.
 *
 * `onBack` у [OnboardingStep] не передаётся ни в одном из режимов: возвращаться
 * с этого экрана некуда, он стартовый в графе.
 *
 * @param onContinue дальше начинается приложение. Вызывается только на мягком
 * предложении (и когда решения в гейте не оказалось вовсе).
 */
@Composable
fun AppUpdateScreen(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppUpdateViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is AppUpdateEffect.OpenStore ->
                    if (!context.openStore(effect.url)) {
                        viewModel.onEvent(AppUpdateEvent.StoreOpenFailed)
                    }

                AppUpdateEffect.Continue -> onContinue()
            }
        }
    }

    AppUpdateContent(
        state = state,
        onEvent = viewModel::onEvent,
        modifier = modifier,
    )
}

/**
 * Открыть магазин. Ссылка уже проверена
 * ([uz.mahalla.feature.update.domain.StoreLink]) — здесь остаётся только
 * случай, когда открыть её нечем: `market://` без единого магазина на
 * устройстве вполне реален в Узбекистане, где прошивки без сервисов Google
 * обычны.
 */
private fun Context.openStore(url: String): Boolean = runCatching {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}.onFailure { if (it !is ActivityNotFoundException) throw it }.isSuccess

@Composable
private fun AppUpdateContent(
    state: AppUpdateState,
    onEvent: (AppUpdateEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val update = state.update
    OnboardingStep(
        title = stringResource(
            if (state.blocking) R.string.update_required_title else R.string.update_available_title,
        ),
        modifier = modifier,
        subtitle = stringResource(
            if (state.blocking) {
                R.string.update_required_description
            } else {
                R.string.update_available_description
            },
        ),
        footer = {
            MahallaButton(
                text = stringResource(R.string.update_action),
                onClick = { onEvent(AppUpdateEvent.UpdateRequested) },
                state = ButtonState(enabled = update?.storeUrl != null),
            )
            // «Позже» существует только там, где отложить действительно можно:
            // на блокирующем экране кнопка, которая ничего не делает, читалась
            // бы как сломанная.
            if (!state.blocking) {
                MahallaButton(
                    text = stringResource(R.string.update_later),
                    onClick = { onEvent(AppUpdateEvent.LaterRequested) },
                    variant = MahallaButtonVariant.Ghost,
                    state = ButtonState(loading = state.skipping),
                )
            }
        },
    ) {
        if (update?.versionName != null) {
            Text(
                text = stringResource(R.string.update_version, update.versionName),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMahallaColors.current.fgMuted,
            )
        }
        if (update?.releaseNotes != null) {
            OnboardingNotice(text = update.releaseNotes)
        }
        if (state.storeFailed) {
            OnboardingError(stringResource(R.string.update_store_unavailable))
        }
    }
}

@ThemeLanguagePreviews
@Composable
private fun AppUpdateRequiredPreview() {
    PreviewSurface {
        AppUpdateContent(
            state = AppUpdateState(
                update = AppUpdate(
                    versionName = "1.4.0",
                    releaseNotes = "Karta tezroq ochiladi, buyurtma holati yangilandi.",
                    storeUrl = "https://play.google.com/store/apps/details?id=uz.mahalla",
                ),
                blocking = true,
            ),
            onEvent = {},
        )
    }
}

@Preview(name = "update · suggested", showBackground = true)
@Composable
private fun AppUpdateSuggestedPreview() {
    PreviewSurface {
        AppUpdateContent(
            state = AppUpdateState(
                update = AppUpdate(
                    versionName = "1.4.0",
                    storeUrl = "https://play.google.com/store/apps/details?id=uz.mahalla",
                    remainingSkips = 2,
                ),
                blocking = false,
            ),
            onEvent = {},
        )
    }
}
