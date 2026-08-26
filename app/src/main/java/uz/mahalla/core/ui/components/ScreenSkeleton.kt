package uz.mahalla.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import uz.mahalla.R
import uz.mahalla.ui.theme.Spacing

/** Кнопка на экране-скелете: до вёрстки по макету их достаточно двух видов. */
data class ScreenAction(
    val label: String,
    val primary: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * Скелет экрана на время каркаса (эпик 1.1): заголовок, подпись и кнопки
 * навигации. Реальная вёрстка 35 экранов идёт следующими эпиками, но граф
 * навигации и переходы уже настоящие и проверяемые руками.
 */
@Composable
fun ScreenSkeleton(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: List<ScreenAction> = emptyList(),
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(Spacing.gap),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content()
        Text(
            text = stringResource(R.string.screen_skeleton_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        actions.forEach { action ->
            if (action.primary) {
                Button(
                    onClick = action.onClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Spacing.minTouch),
                ) {
                    Text(action.label)
                }
            } else {
                OutlinedButton(
                    onClick = action.onClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Spacing.minTouch),
                ) {
                    Text(action.label)
                }
            }
        }
    }
}
