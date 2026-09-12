package uz.mahalla.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
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
 * Хвост пагинируемого списка (issue #214) — раньше `LoadMoreItem` был
 * скопирован в одиннадцать экранов плюс `FashionUi.FashionLoadMore`, побайтово
 * одинаковый везде, кроме типа состояния и события.
 *
 * Два варианта под разный характер списка, а не один на все:
 * - [LoadMoreAuto] — догрузка сама, когда хвост попал в видимую область.
 *   Самоограничивается: перестаёт просить страницы, как только список
 *   перестал расти или хвост ушёл с экрана. Для длинной выдачи браузингового
 *   списка (поиск мест, каталоги мастеров/одежды) отдельная кнопка на каждую
 *   страницу только раздражает.
 * - [LoadMoreButton] — явное «Показать ещё» там, где нужен контроль над
 *   числом запросов: экран одного списка из нескольких источников
 *   (`ActivityScreen`, issue #151), где старый автотриггер был ключом на
 *   курсор — тот сдвигается на каждой странице и перезапускал сам себя,
 *   вычитывая все страницы всех источников одним открытием таба.
 */
@Composable
fun LoadMoreAuto(
    itemCount: Int,
    isLoading: Boolean,
    failure: ApiFailure?,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (failure != null) {
        LoadMoreFailure(
            failure = failure,
            onRetry = onLoadMore,
            modifier = modifier.padding(Spacing.gap),
        )
        return
    }

    LaunchedEffect(itemCount) { onLoadMore() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.gap),
        contentAlignment = Alignment.Center,
    ) {
        // Место под крутилку держится всегда: иначе список дёргается на
        // высоту индикатора каждый раз, когда страница догрузилась.
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(MahallaComponentDefaults.loadMoreIndicatorSize),
                strokeWidth = MahallaComponentDefaults.progressStrokeWidth,
            )
        } else {
            Spacer(modifier = Modifier.size(MahallaComponentDefaults.loadMoreIndicatorSize))
        }
    }
}

@Composable
fun LoadMoreButton(
    isLoading: Boolean,
    failure: ApiFailure?,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (failure != null) {
        LoadMoreFailure(failure = failure, onRetry = onLoadMore, modifier = modifier)
        return
    }

    MahallaButton(
        text = stringResource(R.string.action_load_more),
        onClick = onLoadMore,
        modifier = modifier.padding(vertical = Spacing.item),
        variant = MahallaButtonVariant.Secondary,
        state = if (isLoading) ButtonState.Loading else ButtonState.Default,
    )
}

@Composable
private fun LoadMoreFailure(
    failure: ApiFailure,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
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
        MahallaButton(
            text = stringResource(R.string.action_retry),
            onClick = onRetry,
            variant = MahallaButtonVariant.Secondary,
            fillWidth = false,
        )
    }
}

@ThemeLanguagePreviews
@Composable
private fun LoadMoreAutoPreview() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.gap)) {
            LoadMoreAuto(itemCount = 20, isLoading = false, failure = null, onLoadMore = {})
            LoadMoreAuto(itemCount = 20, isLoading = true, failure = null, onLoadMore = {})
            LoadMoreAuto(
                itemCount = 20,
                isLoading = false,
                failure = ApiFailure(ApiError.NoConnection),
                onLoadMore = {},
            )
        }
    }
}

@ThemeLanguagePreviews
@Composable
private fun LoadMoreButtonPreview() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.gap)) {
            LoadMoreButton(isLoading = false, failure = null, onLoadMore = {})
            LoadMoreButton(isLoading = true, failure = null, onLoadMore = {})
            LoadMoreButton(isLoading = false, failure = ApiFailure(ApiError.NoConnection), onLoadMore = {})
        }
    }
}
