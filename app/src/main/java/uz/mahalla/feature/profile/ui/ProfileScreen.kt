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
import uz.mahalla.core.ui.components.ScreenSkeleton
import uz.mahalla.data.prefs.ThemeMode
import uz.mahalla.ui.theme.Spacing

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                ProfileEffect.RecreateActivity -> (context as? Activity)?.recreate()
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
