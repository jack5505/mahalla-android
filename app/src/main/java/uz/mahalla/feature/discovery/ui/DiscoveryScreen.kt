package uz.mahalla.feature.discovery.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import uz.mahalla.R
import uz.mahalla.core.ui.components.ScreenAction
import uz.mahalla.core.ui.components.ScreenSkeleton

/** Discovery — стартовый экран основного графа (bottom nav). */
@Composable
fun DiscoveryScreen(
    onPlaceClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenSkeleton(
        title = stringResource(R.string.discovery_title),
        modifier = modifier,
        subtitle = stringResource(R.string.discovery_subtitle),
        actions = listOf(
            ScreenAction(
                label = stringResource(R.string.discovery_open_demo_place),
                onClick = { onPlaceClick(DEMO_PLACE_ID) },
            ),
        ),
    )
}

/** До подключения каталога переход в детали проверяется на фиксированном id. */
private const val DEMO_PLACE_ID = "demo-place"
