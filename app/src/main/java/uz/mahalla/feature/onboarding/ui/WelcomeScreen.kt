package uz.mahalla.feature.onboarding.ui

import android.app.Activity
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
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaSegmentedControl
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews

/**
 * Welcome (3.1): что это за приложение, выбор языка и переход к входу.
 *
 * Вход и регистрация — одна кнопка: сценарий один и тот же (номер → код), а
 * есть ли уже аккаунт, знает сервер, не пользователь.
 */
@Composable
fun WelcomeScreen(
    onContinue: () -> Unit,
    onChangeServer: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WelcomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                WelcomeEffect.RecreateActivity -> (context as? Activity)?.recreate()
            }
        }
    }

    WelcomeContent(
        state = state,
        onLanguageSelected = { viewModel.onEvent(WelcomeEvent.LanguageSelected(it)) },
        onContinue = onContinue,
        onChangeServer = onChangeServer,
        modifier = modifier,
    )
}

@Composable
private fun WelcomeContent(
    state: WelcomeState,
    onLanguageSelected: (AppLanguage) -> Unit,
    onContinue: () -> Unit,
    onChangeServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val languages = AppLanguage.entries
    OnboardingStep(
        title = stringResource(R.string.onboarding_welcome_title),
        modifier = modifier,
        subtitle = stringResource(R.string.onboarding_welcome_subtitle),
        footer = {
            MahallaButton(
                text = stringResource(R.string.onboarding_welcome_action),
                onClick = onContinue,
            )
            // Адрес бэкенда (issue #26) вводится до входа, но опечатку в нём
            // видно только здесь — иначе исправить её было бы негде.
            MahallaButton(
                text = stringResource(R.string.backend_url_change),
                onClick = onChangeServer,
                variant = MahallaButtonVariant.Ghost,
            )
        },
    ) {
        Text(
            text = stringResource(R.string.onboarding_welcome_language),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        MahallaSegmentedControl(
            options = languages.map { stringResource(it.labelRes()) },
            selectedIndex = languages.indexOf(state.language),
            onSelect = { index -> onLanguageSelected(languages[index]) },
        )
    }
}

internal fun AppLanguage.labelRes(): Int = when (this) {
    AppLanguage.SYSTEM -> R.string.language_system
    AppLanguage.UZBEK -> R.string.language_uz
    AppLanguage.RUSSIAN -> R.string.language_ru
}

@ThemeLanguagePreviews
@Composable
private fun WelcomeScreenPreview() {
    PreviewSurface {
        WelcomeContent(
            state = WelcomeState(language = AppLanguage.UZBEK),
            onLanguageSelected = {},
            onContinue = {},
            onChangeServer = {},
        )
    }
}
