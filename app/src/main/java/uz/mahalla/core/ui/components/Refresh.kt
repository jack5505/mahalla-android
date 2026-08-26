package uz.mahalla.core.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Pull-to-refresh (эпик 2.3).
 *
 * Обновление поверх готовых данных не переводит экран в
 * [uz.mahalla.core.ui.state.ScreenState.Loading]: список остаётся на месте,
 * скелетон не подменяет уже показанное — иначе каждое обновление выглядит как
 * повторная загрузка экрана.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MahallaPullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
        content = content,
    )
}
