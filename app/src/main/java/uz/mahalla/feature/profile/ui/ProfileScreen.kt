package uz.mahalla.feature.profile.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.locale.AppLanguage
import uz.mahalla.core.ui.components.MahallaListItem
import uz.mahalla.core.ui.components.ScreenSkeleton
import uz.mahalla.data.prefs.ThemeMode
import uz.mahalla.ui.theme.Spacing

/**
 * Профиль: язык, тема и — в сборках, которым это разрешено, — адрес сервера.
 *
 * @param onChangeServer открыть экран адреса бэкенда (issue #26); `null` —
 * сборке менять адрес не разрешено, строки нет. После входа welcome с той же
 * кнопкой недостижим, а сервер сменить бывает нужно (переехал стенд).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    modifier: Modifier = Modifier,
    onChangeServer: (() -> Unit)? = null,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                ProfileEffect.RecreateActivity -> (context as? Activity)?.recreate()
                is ProfileEffect.OpenHttpInspector -> context.startActivity(effect.intent)
            }
        }
    }

    ScreenSkeleton(
        title = stringResource(R.string.profile_title),
        modifier = modifier,
        subtitle = stringResource(R.string.profile_subtitle),
    ) {
        Text(
            text = stringResource(R.string.profile_language),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.gap)) {
            AppLanguage.entries.forEach { language ->
                FilterChip(
                    selected = state.settings.language == language,
                    onClick = { viewModel.onEvent(ProfileEvent.LanguageSelected(language)) },
                    label = { Text(stringResource(language.labelRes())) },
                )
            }
        }

        Text(
            text = stringResource(R.string.profile_theme),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.gap)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = state.settings.themeMode == mode,
                    onClick = { viewModel.onEvent(ProfileEvent.ThemeSelected(mode)) },
                    label = { Text(stringResource(mode.labelRes())) },
                )
            }
        }

        if (onChangeServer != null) {
            MahallaListItem(
                title = stringResource(R.string.backend_url_change),
                // Показываем адрес, на который приложение ходит сейчас: без
                // него строка не отвечает на главный вопрос «а куда сейчас?».
                subtitle = state.settings.backendBaseUrl
                    ?: stringResource(R.string.backend_url_default_value),
                onClick = onChangeServer,
            )
        }

        // Инспектор трафика (issue #30): в release строки нет — библиотека
        // приезжает вариантом no-op и отвечает `isAvailable = false`.
        if (state.httpInspectorAvailable) {
            MahallaListItem(
                title = stringResource(R.string.http_inspector_open),
                subtitle = stringResource(R.string.http_inspector_subtitle),
                onClick = { viewModel.onEvent(ProfileEvent.HttpInspectorRequested) },
            )
        }
    }
}

private fun AppLanguage.labelRes(): Int = when (this) {
    AppLanguage.SYSTEM -> R.string.language_system
    AppLanguage.UZBEK -> R.string.language_uz
    AppLanguage.RUSSIAN -> R.string.language_ru
}

private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}
