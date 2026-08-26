package uz.mahalla.feature.orders.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import uz.mahalla.R
import uz.mahalla.core.ui.components.ScreenSkeleton

@Composable
fun OrdersScreen(modifier: Modifier = Modifier) {
    ScreenSkeleton(
        title = stringResource(R.string.orders_title),
        modifier = modifier,
        subtitle = stringResource(R.string.orders_subtitle),
    )
}
