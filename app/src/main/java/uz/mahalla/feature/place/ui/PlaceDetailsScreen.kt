package uz.mahalla.feature.place.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import uz.mahalla.R
import uz.mahalla.core.ui.components.ScreenAction
import uz.mahalla.core.ui.components.ScreenSkeleton

/**
 * Карточка заведения — точка входа deep link'а `mahalla://place/{placeId}`.
 * Показ id тут не для красоты: так вручную проверяется, что аргумент дошёл
 * из ссылки в typed route.
 */
@Composable
fun PlaceDetailsScreen(
    placeId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenSkeleton(
        title = stringResource(R.string.place_title),
        modifier = modifier,
        subtitle = stringResource(R.string.place_id_label, placeId),
        actions = listOf(
            ScreenAction(
                label = stringResource(R.string.action_back),
                primary = false,
                onClick = onBack,
            ),
        ),
    )
}
