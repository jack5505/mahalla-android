package uz.mahalla.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import uz.mahalla.R
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.userMessage
import uz.mahalla.ui.theme.Spacing

/**
 * Отказ одного блока экрана, а не всего экрана (issue #272 — четвёртая
 * побайтовая копия свелась к этой). `ApiErrorState` тут не годится по двум
 * причинам: он подменяет собой весь контент, а здесь отказать должен только
 * фрагмент — один из нескольких независимых источников на экране (форма,
 * точечное действие, один из списков) — пока остальной экран остаётся
 * рабочим; и он прокручивается сам, а вложенная прокрутка внутри
 * `LazyColumn` (панель бизнеса кладёт этот компонент строкой списка) меряется
 * бесконечной высотой и роняет измерение (issue #62).
 */
@Composable
fun InlineFailure(
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
                fillWidth = false,
            )
        }
    }
}

@ThemeLanguagePreviews
@Composable
private fun InlineFailurePreview() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.gap)) {
            InlineFailure(failure = ApiFailure(ApiError.NoConnection))
            InlineFailure(failure = ApiFailure(ApiError.NoConnection), onRetry = {})
        }
    }
}
