package uz.mahalla.feature.onboarding.ui

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.locale.AppLanguage
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaSegmentedControl
import uz.mahalla.core.ui.preview.LargeFontPreviews
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.ui.theme.FocusGradientEnd
import uz.mahalla.ui.theme.FocusGradientStart
import uz.mahalla.ui.theme.FocusGradientWelcomeEnd
import uz.mahalla.ui.theme.FocusHeadlineWelcome
import uz.mahalla.ui.theme.MahallaTheme
import uz.mahalla.ui.theme.Spacing

/**
 * Welcome (3.1): полноэкранный градиентный экран — что это за приложение,
 * выбор языка и переход к входу.
 *
 * Вход и регистрация — одна кнопка: сценарий один и тот же (номер → код), а
 * есть ли уже аккаунт, знает сервер, не пользователь.
 *
 * Экран не переиспользует общий [OnboardingStep] (белый фон, шапка с
 * «назад»): здесь нет ни шапки, ни первого шага, куда возвращаться —
 * приветствие полноэкранное, по `design_handoff_mahalla_focus/README.md`
 * (0a).
 *
 * @param onChangeServer открыть экран адреса бэкенда (issue #26); `null` —
 * сборке менять адрес не разрешено, кнопки нет.
 */
@Composable
fun WelcomeScreen(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    onChangeServer: (() -> Unit)? = null,
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
        modifier = modifier,
        onChangeServer = onChangeServer,
    )
}

@Composable
private fun WelcomeContent(
    state: WelcomeState,
    onLanguageSelected: (AppLanguage) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    onChangeServer: (() -> Unit)? = null,
) {
    val languages = AppLanguage.entries
    val onGradient = Color(0xFFE8DEFF) // primaryContainer — вторичный текст на градиенте

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    0f to FocusGradientStart,
                    0.7f to FocusGradientEnd,
                    1f to FocusGradientWelcomeEnd,
                ),
            ),
    ) {
        // Два декоративных кольца справа — чисто орнамент, из семантики исключены.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 90.dp, y = (-60).dp)
                .size(280.dp)
                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)), CircleShape),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 40.dp, y = 40.dp)
                .size(160.dp)
                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)), CircleShape),
        )

        // Экран прокручивается, но только когда содержимое не влезло:
        // `heightIn(min = maxHeight)` держит колонку ровно в высоту экрана,
        // пока места хватает, — и тогда работает прижатие к низу. На крупном
        // системном шрифте (заголовок 36sp, две кнопки, сегмент языка и
        // подпись согласия) колонка перерастает экран и начинает скроллиться,
        // а не обрезает заголовок, как это делал `fillMaxSize` без прокрутки.
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "M",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Column(
                modifier = Modifier
                    .padding(top = Spacing.gap)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    text = stringResource(R.string.onboarding_welcome_kicker),
                    style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.66.sp),
                    color = onGradient,
                )
                Text(
                    text = stringResource(R.string.onboarding_welcome_title),
                    modifier = Modifier
                        .padding(top = Spacing.item)
                        .semantics { heading() },
                    style = FocusHeadlineWelcome,
                    color = Color.White,
                )
                Text(
                    text = stringResource(R.string.onboarding_welcome_subtitle),
                    modifier = Modifier.padding(top = Spacing.item),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onGradient,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
                Text(
                    text = stringResource(R.string.onboarding_welcome_language),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                )
                MahallaSegmentedControl(
                    options = languages.map { stringResource(it.labelRes()) },
                    selectedIndex = languages.indexOf(state.language),
                    onSelect = { index -> onLanguageSelected(languages[index]) },
                )
                MahallaButton(
                    text = stringResource(R.string.onboarding_welcome_action),
                    onClick = onContinue,
                    variant = MahallaButtonVariant.OnColor,
                )
                // Адрес бэкенда (issue #26) вводится до входа, но опечатку в нём
                // видно только здесь — иначе исправить её было бы негде.
                // Dev-только affordance: дизайн его не описывает, поэтому
                // видимость на градиенте здесь вторична.
                if (onChangeServer != null) {
                    MahallaButton(
                        text = stringResource(R.string.backend_url_change),
                        onClick = onChangeServer,
                        variant = MahallaButtonVariant.Ghost,
                    )
                }
                Text(
                    text = stringResource(R.string.onboarding_welcome_consent),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall,
                    color = onGradient,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

internal fun AppLanguage.labelRes(): Int = when (this) {
    AppLanguage.SYSTEM -> R.string.language_system
    AppLanguage.UZBEK -> R.string.language_uz
    AppLanguage.RUSSIAN -> R.string.language_ru
}

@ThemeLanguagePreviews
@LargeFontPreviews
@Composable
private fun WelcomeScreenPreview() {
    MahallaTheme {
        WelcomeContent(
            state = WelcomeState(language = AppLanguage.UZBEK),
            onLanguageSelected = {},
            onContinue = {},
            onChangeServer = {},
        )
    }
}
