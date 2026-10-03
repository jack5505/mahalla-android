package uz.mahalla.feature.order.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.components.ScreenStateHost
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState

/**
 * Ничего не рисует по существу (issue #343): пока грузится — крутилка, дальше
 * [onResolved] сразу уводит на найденный экран. Отдельный маршрут, а не часть
 * `OrderStatusScreen`, нужен потому, что вертикаль до ответа сервера не
 * известна, а `NavHost` требует назначение для deep link'а сразу.
 */
@Composable
fun OrderDeepLinkScreen(
    onResolved: (OrderDeepLinkDestination) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OrderDeepLinkViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        (state as? ScreenState.Content)?.data?.let(onResolved)
    }

    OrderDeepLinkContent(state = state, onRetry = viewModel::retry, modifier = modifier)
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun OrderDeepLinkContent(
    state: ScreenState<OrderDeepLinkDestination>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        ScreenStateHost(
            state = state,
            onRetry = onRetry,
            loading = { CircularProgressIndicator() },
            content = {},
        )
    }
}

@ThemeLanguagePreviews
@Composable
private fun OrderDeepLinkLoadingPreview() {
    PreviewSurface {
        OrderDeepLinkContent(state = ScreenState.Loading, onRetry = {})
    }
}

@ThemeLanguagePreviews
@Composable
private fun OrderDeepLinkErrorPreview() {
    PreviewSurface {
        OrderDeepLinkContent(state = ScreenState.Error(ApiFailure(ApiError.NoConnection)), onRetry = {})
    }
}
