package uz.mahalla.feature.fashion.ui.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.format.DateTimeFormatters
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.EmptyState
import uz.mahalla.core.ui.components.ListSkeleton
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaDialog
import uz.mahalla.core.ui.components.MahallaPullToRefresh
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.fashion.ui.FashionFailure
import uz.mahalla.feature.fashion.ui.FashionLoadMore
import uz.mahalla.feature.fashion.ui.labelRes
import uz.mahalla.feature.fashion.ui.priceText
import uz.mahalla.feature.fashion.ui.tone
import uz.mahalla.feature.food.domain.CartLine
import uz.mahalla.feature.food.domain.CartTotals
import uz.mahalla.feature.food.domain.DeliveryMethod
import uz.mahalla.feature.food.domain.Order
import uz.mahalla.feature.food.domain.OrderStatus
import uz.mahalla.feature.food.domain.OrderStatusFlow
import uz.mahalla.feature.food.domain.PaymentMethod
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums
import java.time.Instant

/**
 * «Мои заказы одежды» (issue #108): состав, статус и отмена.
 *
 * Своего экрана у одного заказа нет: `OrderView` отдаёт в списке всё, что
 * можно показать, — состав, суммы и статус. Появится, когда бэкенд начнёт
 * отдавать по заказу что-то ещё (трек-номер, историю статусов).
 */
@Composable
fun FashionOrdersScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FashionOrdersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Статус двигает магазин, пока приложение в фоне, — а увидеть именно это
    // сюда и приходят.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(FashionOrdersEvent.ScreenResumed)
    }

    FashionOrdersContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun FashionOrdersContent(
    state: FashionOrdersState,
    onEvent: (FashionOrdersEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(title = stringResource(R.string.fashion_orders_title), onBack = onBack)

        MahallaPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(FashionOrdersEvent.Refreshed) },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.gap),
            ) {
                state.cancelFailure?.let { failure ->
                    item(key = "cancel-failure") { FashionFailure(failure = failure) }
                }
                orderItems(state = state, onEvent = onEvent)
            }
        }
    }

    state.confirmCancel?.let {
        MahallaDialog(
            title = stringResource(R.string.fashion_order_cancel_title),
            text = stringResource(R.string.fashion_order_cancel_message),
            confirmLabel = stringResource(R.string.fashion_order_cancel),
            onConfirm = { onEvent(FashionOrdersEvent.CancelConfirmed) },
            onDismiss = { onEvent(FashionOrdersEvent.CancelDismissed) },
            dismissLabel = stringResource(R.string.fashion_order_cancel_keep),
            destructive = true,
        )
    }
}

/**
 * Состояния разложены руками, а не через `ScreenStateHost`: тот рисует
 * `ApiErrorState` с собственной прокруткой, а внутри `LazyColumn` вложенная
 * прокрутка меряется бесконечной высотой (issue #62).
 */
private fun LazyListScope.orderItems(
    state: FashionOrdersState,
    onEvent: (FashionOrdersEvent) -> Unit,
) {
    when (val orders = state.orders) {
        is ScreenState.Loading -> item(key = "loading") { ListSkeleton(itemCount = LIST_SKELETONS) }

        is ScreenState.Empty -> item(key = "empty") {
            EmptyState(
                title = stringResource(R.string.fashion_orders_empty_title),
                description = stringResource(R.string.fashion_orders_empty_description),
                icon = Icons.Outlined.Checkroom,
            )
        }

        is ScreenState.Error -> item(key = "error") {
            FashionFailure(
                failure = orders.failure,
                onRetry = { onEvent(FashionOrdersEvent.Retry) },
            )
        }

        is ScreenState.Content -> {
            items(orders.data, key = Order::id) { order ->
                OrderCard(
                    order = order,
                    pending = state.pendingCancelId == order.id,
                    // Пока идёт отмена по одной строке, остальные не трогаем:
                    // ответы приехали бы на список, которого уже нет.
                    enabled = state.pendingCancelId == null,
                    onEvent = onEvent,
                )
            }
            if (state.hasMore || state.loadMoreFailure != null) {
                item(key = "load-more") {
                    FashionLoadMore(
                        itemCount = orders.data.size,
                        failure = state.loadMoreFailure,
                        onLoadMore = { onEvent(FashionOrdersEvent.LoadMore) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OrderCard(
    order: Order,
    pending: Boolean,
    enabled: Boolean,
    onEvent: (FashionOrdersEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMahallaColors.current
    MahallaCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                // Номер заказа — то, что называют в поддержке. Его нет —
                // подписываем датой, а не обрезком uuid.
                text = order.number
                    ?: order.createdAt?.let(DateTimeFormatters::dateTime)
                    ?: stringResource(R.string.fashion_order_unnamed),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium.merge(TabularNums),
                color = MaterialTheme.colorScheme.onSurface,
            )
            MahallaBadge(
                text = stringResource(order.status.labelRes()),
                tone = order.status.tone(),
            )
        }

        order.lines.forEach { line ->
            Text(
                text = stringResource(
                    R.string.fashion_order_line,
                    line.name.takeIf(String::isNotBlank)
                        ?: stringResource(R.string.fashion_product_unnamed),
                    line.quantity,
                ),
                modifier = Modifier.padding(top = Spacing.item / 2),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fgMuted,
            )
        }

        Text(
            text = priceText(order.totals.totalSum),
            modifier = Modifier.padding(top = Spacing.item),
            style = MaterialTheme.typography.titleMedium.merge(TabularNums),
            color = MaterialTheme.colorScheme.onSurface,
        )

        if (OrderStatusFlow.canCancel(order.status)) {
            MahallaButton(
                text = stringResource(R.string.fashion_order_cancel),
                onClick = { onEvent(FashionOrdersEvent.CancelRequested(order.id)) },
                modifier = Modifier.padding(top = Spacing.item),
                variant = MahallaButtonVariant.Destructive,
                state = ButtonState(enabled = enabled && !pending, loading = pending),
            )
        }
    }
}

private const val LIST_SKELETONS = 3

@ThemeLanguagePreviews
@Composable
private fun FashionOrdersPreview() {
    val order = Order(
        id = "o-1",
        placeId = "s-1",
        placeName = "",
        number = "CL-2026-0042",
        status = OrderStatus.Confirmed,
        method = DeliveryMethod.Delivery,
        payment = PaymentMethod.Wallet,
        totals = CartTotals(subtotalSum = 480_000, deliverySum = 20_000),
        lines = listOf(
            CartLine(
                id = "v-1",
                itemId = "v-1",
                name = "Oq ko'ylak",
                unitPriceSum = 240_000,
                quantity = 2,
            ),
        ),
        createdAt = Instant.parse("2026-09-05T09:00:00Z"),
    )
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        FashionOrdersContent(
            state = FashionOrdersState(orders = ScreenState.Content(listOf(order))),
            onEvent = {},
            onBack = {},
        )
    }
}
