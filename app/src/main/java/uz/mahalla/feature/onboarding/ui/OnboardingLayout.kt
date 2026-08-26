package uz.mahalla.feature.onboarding.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing

/**
 * Общий каркас шагов онбординга (эпик 3): заголовок сверху, контент в
 * прокрутке, кнопки прижаты к низу.
 *
 * Один каркас на шесть экранов, потому что все они устроены одинаково, и
 * расхождение отступов между ними — самая заметная глазом ошибка вёрстки.
 * `imePadding` обязателен: на экранах телефона и кода клавиатура иначе
 * накрывает кнопку.
 */
@Composable
fun OnboardingStep(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    footer: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        if (onBack != null) {
            // Заголовок шага живёт в теле экрана — в панели остаётся только
            // «назад», иначе название дублируется и читается TalkBack дважды.
            MahallaTopBar(title = "", onBack = onBack)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.gap),
        ) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalMahallaColors.current.fgMuted,
                )
            }
            content()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.gutter, vertical = Spacing.gap),
            verticalArrangement = Arrangement.spacedBy(Spacing.item),
        ) {
            footer()
        }
    }
}

/**
 * Ошибка шага под полем ввода. `liveRegion` — TalkBack проговаривает
 * появившуюся ошибку сам, без повторного обхода экрана.
 */
@Composable
fun OnboardingError(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}
