package uz.mahalla.feature.business.ui.orders

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.format.DateTimeFormatters
import uz.mahalla.core.format.MoneyFormatter
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.EmptyState
import uz.mahalla.core.ui.components.FilterChipUi
import uz.mahalla.core.ui.components.ListSkeleton
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaFilterRow
import uz.mahalla.core.ui.components.MahallaPullToRefresh
import uz.mahalla.core.ui.components.MahallaSnackbarHost
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.rememberSnackbarController
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.business.domain.BusinessOrder
import uz.mahalla.feature.business.domain.BusinessOrderFilter
import uz.mahalla.feature.business.domain.BusinessOrderLine
import uz.mahalla.feature.business.domain.BusinessOrderStatusFlow
import uz.mahalla.feature.business.ui.BusinessInlineFailure
import uz.mahalla.feature.food.domain.DeliveryMethod
import uz.mahalla.feature.food.domain.OrderStatus
import uz.mahalla.feature.food.domain.PaymentMethod
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import java.time.Instant

/**
 * Входящие заказы (задача 12.3): состав, суммы и кнопки движения по статусам.
 *
 * Кнопок ровно столько, сколько разрешает домен: «готов» у заказа, который ещё
 * не приняли, — это предложение перепрыгнуть шаг, о котором клиент не узнает.
 */
@Composable
fun BusinessOrdersScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BusinessOrdersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = rememberSnackbarController()
    val context = LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(BusinessOrdersEvent.ScreenResumed)
    }

    LaunchedEffect(viewModel, context) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is BusinessOrdersEffect.StatusChanged -> snackbar.show(
                    text = context.getString(
                        R.string.business_orders_status_changed,
                        effect.number ?: context.getString(R.string.business_orders_no_number),
                        context.getString(effect.status.labelRes()),
                    ),
                    tone = if (effect.status == OrderStatus.Cancelled) {
                        MahallaTone.Warning
                    } else {
                        MahallaTone.Success
                    },
                )
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        BusinessOrdersContentScreen(
            state = state,
            onEvent = viewModel::onEvent,
            onBack = onBack,
        )
        MahallaSnackbarHost(
            controller = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun BusinessOrdersContentScreen(
    state: BusinessOrdersState,
    onEvent: (BusinessOrdersEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(
            title = state.placeName.takeIf(String::isNotBlank)
                ?: stringResource(R.string.business_section_orders),
            onBack = onBack,
        )
        // Счётчик новых — в шапке: на вкладке «все» иначе не видно, что кухню
        // кто-то ждёт.
        if (state.newCount > 0) {
            Text(
                text = stringResource(R.string.business_orders_new_count, state.newCount),
                modifier = Modifier.padding(horizontal = Spacing.gutter),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMahallaColors.current.fgMuted,
            )
        }
        MahallaFilterRow(
            items = BusinessOrderFilter.entries.map { filter ->
                FilterChipUi(id = filter.name, label = stringResource(filter.labelRes()))
            },
            selectedId = state.filter.name,
            onSelect = { id ->
                BusinessOrderFilter.entries.firstOrNull { it.name == id }?.let { filter ->
                    onEvent(BusinessOrdersEvent.FilterSelected(filter))
                }
            },
        )
        MahallaPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(BusinessOrdersEvent.Refreshed) },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.gap),
            ) {
                state.actionFailure?.let { failure ->
                    item(key = "action-failure") { BusinessInlineFailure(failure = failure) }
                }
                orderItems(state = state, onEvent = onEvent)
            }
        }
    }
}

private fun LazyListScope.orderItems(
    state: BusinessOrdersState,
    onEvent: (BusinessOrdersEvent) -> Unit,
) {
    when (val orders = state.orders) {
        is ScreenState.Loading -> item(key = "loading") { ListSkeleton(itemCount = 3) }

        is ScreenState.Empty -> item(key = "empty") {
            EmptyState(
                title = stringResource(R.string.business_orders_empty_title),
                description = stringResource(R.string.business_orders_empty_description),
                icon = Icons.Outlined.ReceiptLong,
            )
        }

        is ScreenState.Error -> item(key = "error") {
            BusinessInlineFailure(
                failure = orders.failure,
                onRetry = { onEvent(BusinessOrdersEvent.Retry) },
            )
        }

        is ScreenState.Content -> {
            items(orders.data, key = BusinessOrder::id) { order ->
                BusinessOrderCard(
                    order = order,
                    pending = state.pendingOrderId == order.id,
                    enabled = !state.isBusy,
                    onEvent = onEvent,
                )
            }
            if (state.hasMore || state.loadMoreFailure != null) {
                item(key = "load-more") {
                    LoadMoreItem(
                        state = state,
                        itemCount = orders.data.size,
                        onEvent = onEvent,
                    )
                }
            }
        }
    }
}

/** Карточка заказа: номер, статус, состав, сумма и кнопки следующего шага. */
@Composable
private fun BusinessOrderCard(
    order: BusinessOrder,
    pending: Boolean,
    enabled: Boolean,
    onEvent: (BusinessOrdersEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMahallaColors.current
    val currency = stringResource(R.string.currency_uzs)
    MahallaCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = order.number ?: stringResource(R.string.business_orders_no_number),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            MahallaBadge(
                text = stringResource(order.status.labelRes()),
                tone = order.status.tone(),
            )
        }

        Text(
            text = stringResource(
                R.string.business_orders_meta,
                stringResource(order.method.labelRes()),
                stringResource(order.payment.labelRes()),
            ),
            modifier = Modifier.padding(top = Spacing.item),
            style = MaterialTheme.typography.bodySmall,
            color = colors.fgMuted,
        )

        order.createdAt?.let { created ->
            Text(
                text = DateTimeFormatters.time(created),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgMuted,
            )
        }

        order.address?.let { address ->
            Text(
                text = address,
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgMuted,
            )
        }

        // Состав — то, ради чего кухня и открывает заказ.
        order.lines.forEachIndexed { index, line ->
            OrderLineRow(
                line = line,
                currency = currency,
                modifier = Modifier.padding(top = if (index == 0) Spacing.gap else Spacing.item),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.gap),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.business_orders_total),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fgMuted,
            )
            Text(
                text = MoneyFormatter.withCurrency(order.totalSum, currency),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        val next = BusinessOrderStatusFlow.nextStatuses(order.status, order.method)
        if (next.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.gap),
                horizontalArrangement = Arrangement.spacedBy(Spacing.item),
            ) {
                next.forEach { status ->
                    MahallaButton(
                        text = stringResource(status.actionRes()),
                        onClick = {
                            onEvent(BusinessOrdersEvent.StatusSelected(order.id, status))
                        },
                        modifier = Modifier.weight(1f),
                        // Отмена — не такая же заметная кнопка, как «принять».
                        variant = if (status == OrderStatus.Cancelled) {
                            MahallaButtonVariant.Ghost
                        } else {
                            MahallaButtonVariant.Primary
                        },
                        state = ButtonState(
                            enabled = enabled,
                            loading = pending,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun OrderLineRow(
    line: BusinessOrderLine,
    currency: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMahallaColors.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.item),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.business_orders_line, line.quantity, line.name),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = MoneyFormatter.withCurrency(line.totalPriceSum, currency),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.fgMuted,
        )
    }
}

/**
 * Хвост списка: догрузка по достижению конца. Провал показывает кнопку с
 * причиной — автотриггер по `itemCount` больше не сработает, список ведь не
 * вырос (то же решение, что в «моих заведениях», issue #94).
 */
@Composable
private fun LoadMoreItem(
    state: BusinessOrdersState,
    itemCount: Int,
    onEvent: (BusinessOrdersEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val failure = state.loadMoreFailure
    if (failure != null) {
        BusinessInlineFailure(
            failure = failure,
            onRetry = { onEvent(BusinessOrdersEvent.LoadMore) },
            modifier = modifier,
        )
        return
    }

    LaunchedEffect(itemCount) { onEvent(BusinessOrdersEvent.LoadMore) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.gap),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(LOAD_MORE_INDICATOR))
    }
}

@StringRes
private fun BusinessOrderFilter.labelRes(): Int = when (this) {
    BusinessOrderFilter.All -> R.string.business_orders_filter_all
    BusinessOrderFilter.New -> R.string.business_orders_filter_new
    BusinessOrderFilter.Accepted -> R.string.business_orders_filter_accepted
    BusinessOrderFilter.Preparing -> R.string.business_orders_filter_preparing
    BusinessOrderFilter.Ready -> R.string.business_orders_filter_ready
}

/** Подпись статуса — состояние («принят»), а не действие. */
@StringRes
internal fun OrderStatus.labelRes(): Int = when (this) {
    OrderStatus.Created -> R.string.business_orders_status_new
    OrderStatus.Confirmed -> R.string.business_orders_status_accepted
    OrderStatus.Preparing -> R.string.business_orders_status_preparing
    OrderStatus.ReadyForPickup -> R.string.business_orders_status_ready
    OrderStatus.Delivering -> R.string.business_orders_status_delivering
    OrderStatus.Completed -> R.string.business_orders_status_completed
    OrderStatus.Cancelled -> R.string.business_orders_status_cancelled
    OrderStatus.Refunded -> R.string.business_orders_status_refunded
    OrderStatus.Unknown -> R.string.business_orders_status_unknown
}

/**
 * Подпись кнопки — действие («принять»), а не состояние. Отдельно от
 * [labelRes]: «принят» на кнопке читается как текущее состояние заказа.
 */
@StringRes
private fun OrderStatus.actionRes(): Int = when (this) {
    OrderStatus.Confirmed -> R.string.business_orders_action_accept
    OrderStatus.Preparing -> R.string.business_orders_action_prepare
    OrderStatus.ReadyForPickup -> R.string.business_orders_action_ready
    OrderStatus.Delivering -> R.string.business_orders_action_deliver
    OrderStatus.Completed -> R.string.business_orders_action_complete
    OrderStatus.Cancelled -> R.string.business_orders_action_cancel

    // В кнопки эти статусы не попадают — `nextStatuses` их не предлагает.
    OrderStatus.Created, OrderStatus.Refunded, OrderStatus.Unknown ->
        R.string.business_orders_status_unknown
}

private fun OrderStatus.tone(): MahallaTone = when (this) {
    OrderStatus.Created -> MahallaTone.Warning
    OrderStatus.Completed -> MahallaTone.Success
    OrderStatus.Cancelled, OrderStatus.Refunded -> MahallaTone.Error
    OrderStatus.Confirmed, OrderStatus.Preparing, OrderStatus.ReadyForPickup,
    OrderStatus.Delivering, OrderStatus.Unknown,
    -> MahallaTone.Neutral
}

@StringRes
private fun DeliveryMethod.labelRes(): Int = when (this) {
    DeliveryMethod.Delivery -> R.string.checkout_method_delivery
    DeliveryMethod.Pickup -> R.string.checkout_method_pickup
}

@StringRes
private fun PaymentMethod.labelRes(): Int = when (this) {
    PaymentMethod.Wallet -> R.string.checkout_payment_wallet
    PaymentMethod.Cash -> R.string.checkout_payment_cash
}

private val LOAD_MORE_INDICATOR = 24.dp

@ThemeLanguagePreviews
@Composable
private fun BusinessOrdersScreenPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        BusinessOrdersContentScreen(
            state = BusinessOrdersState(
                placeName = "Osh Markazi",
                orders = ScreenState.Content(
                    listOf(
                        BusinessOrder(
                            id = "o-1",
                            number = "F-2026-0042",
                            status = OrderStatus.Created,
                            method = DeliveryMethod.Delivery,
                            payment = PaymentMethod.Cash,
                            totalSum = 84_000,
                            address = "Chilonzor, 12-kvartal, 4-uy",
                            lines = listOf(
                                BusinessOrderLine("i-1", "Osh", 2, 32_000, 64_000),
                                BusinessOrderLine("i-2", "Choy", 2, 10_000, 20_000),
                            ),
                            createdAt = Instant.parse("2026-09-09T09:15:00Z"),
                        ),
                        BusinessOrder(
                            id = "o-2",
                            number = "F-2026-0041",
                            status = OrderStatus.Preparing,
                            method = DeliveryMethod.Pickup,
                            payment = PaymentMethod.Wallet,
                            totalSum = 32_000,
                            lines = listOf(BusinessOrderLine("i-1", "Osh", 1, 32_000, 32_000)),
                            createdAt = Instant.parse("2026-09-09T08:50:00Z"),
                        ),
                    ),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
