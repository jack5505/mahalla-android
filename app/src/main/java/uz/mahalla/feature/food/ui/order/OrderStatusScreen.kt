package uz.mahalla.feature.food.ui.order

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.format.DateTimeFormatters
import uz.mahalla.core.format.MoneyFormatter
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaComponentDefaults
import uz.mahalla.core.ui.components.MahallaDialog
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.ScreenStateHost
import uz.mahalla.core.ui.components.SectionHeader
import uz.mahalla.feature.food.domain.CartLine
import uz.mahalla.feature.food.domain.Order
import uz.mahalla.feature.food.domain.OrderStatus
import uz.mahalla.feature.food.domain.OrderStatusFlow
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums

/**
 * Статус заказа (эпик 5.4): этапы, состав, отмена и повтор.
 */
@Composable
fun OrderStatusScreen(
    onOpenCart: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OrderStatusViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is OrderStatusEffect.OpenCart -> onOpenCart(effect.placeId)
                OrderStatusEffect.NavigateBack -> onBack()
            }
        }
    }

    // Опрос статуса живёт ровно столько, сколько экран виден: в фоне статус
    // всё равно некому показать, а запросы идут каждые пять секунд.
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        viewModel.onEvent(OrderStatusEvent.ScreenStarted)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.onEvent(OrderStatusEvent.ScreenStopped)
    }

    OrderStatusContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun OrderStatusContent(
    state: OrderStatusState,
    onEvent: (OrderStatusEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currency = stringResource(R.string.currency_uzs)
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(title = stringResource(R.string.order_title), onBack = onBack)

        ScreenStateHost(
            state = state.order,
            onRetry = { onEvent(OrderStatusEvent.Retry) },
            modifier = Modifier.weight(1f),
        ) { order ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.gap),
                contentPadding = PaddingValues(vertical = Spacing.gap),
            ) {
                item(key = "header") { OrderHeader(order = order) }
                item(key = "stages") { StagesBlock(state = state, order = order) }

                item(key = "items-header") {
                    SectionHeader(title = stringResource(R.string.order_items_title))
                }
                items(count = order.lines.size, key = { order.lines[it].id }) { index ->
                    OrderLineRow(line = order.lines[index], currency = currency)
                }

                item(key = "totals") { TotalsCard(order = order, currency = currency) }

                if (state.cancelFailed) {
                    item(key = "cancel-error") {
                        Text(
                            text = stringResource(R.string.order_cancel_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                if (state.repeatFailed) {
                    item(key = "repeat-error") {
                        Text(
                            text = stringResource(R.string.order_repeat_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                item(key = "actions") {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
                        if (state.canRepeat || state.isRepeating) {
                            MahallaButton(
                                text = stringResource(R.string.order_repeat),
                                onClick = { onEvent(OrderStatusEvent.RepeatClicked) },
                                state = ButtonState(loading = state.isRepeating),
                            )
                        }
                        if (state.canCancel || state.isCancelling) {
                            MahallaButton(
                                text = stringResource(R.string.order_cancel),
                                onClick = { onEvent(OrderStatusEvent.CancelClicked) },
                                variant = MahallaButtonVariant.Destructive,
                                state = ButtonState(loading = state.isCancelling),
                            )
                        }
                    }
                }
            }
        }
    }

    if (state.cancelConfirmVisible) {
        MahallaDialog(
            title = stringResource(R.string.order_cancel_confirm_title),
            text = stringResource(R.string.order_cancel_confirm_description),
            confirmLabel = stringResource(R.string.order_cancel),
            onConfirm = { onEvent(OrderStatusEvent.CancelConfirmed) },
            onDismiss = { onEvent(OrderStatusEvent.CancelDismissed) },
            dismissLabel = stringResource(R.string.order_cancel_keep),
            destructive = true,
        )
    }
}

@Composable
private fun OrderHeader(order: Order, modifier: Modifier = Modifier) {
    MahallaCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = order.placeName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = DateTimeFormatters.dateTime(order.createdAt),
                    style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
                    color = LocalMahallaColors.current.fgMuted,
                )
            }
            MahallaBadge(text = stringResource(order.status.labelRes()), tone = order.status.tone())
        }
        if (order.etaMinutes != null && !OrderStatusFlow.isFinal(order.status)) {
            Text(
                text = stringResource(R.string.order_eta, order.etaMinutes),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMahallaColors.current.fgMuted,
            )
        }
    }
}

/**
 * Этапы. Отменённый заказ цепочку не рисует: он вышел из неё, и подсвеченный
 * «готовится» под надписью «отменён» — прямое противоречие.
 */
@Composable
private fun StagesBlock(
    state: OrderStatusState,
    order: Order,
    modifier: Modifier = Modifier,
) {
    if (order.status == OrderStatus.Cancelled || order.status == OrderStatus.Unknown) return

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
        state.stages.forEach { stage ->
            val done = OrderStatusFlow.isStageDone(stage, order.status, order.method)
            val current = stage == order.status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.item),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (done) {
                        Icons.Outlined.CheckCircle
                    } else {
                        Icons.Outlined.RadioButtonUnchecked
                    },
                    // Состояние этапа читается из текста рядом — иконка
                    // декоративная и для TalkBack пустая.
                    contentDescription = null,
                    modifier = Modifier.size(MahallaComponentDefaults.cardIconSize),
                    tint = when {
                        done -> LocalMahallaColors.current.success
                        current -> LocalMahallaColors.current.accent
                        else -> LocalMahallaColors.current.fgMuted
                    },
                )
                Text(
                    text = stringResource(stage.labelRes()),
                    style = if (current) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = if (done || current) {
                        MaterialTheme.colorScheme.onBackground
                    } else {
                        LocalMahallaColors.current.fgMuted
                    },
                )
            }
        }
    }
}

@Composable
private fun OrderLineRow(line: CartLine, currency: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.gap),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.order_line_name, line.name, line.quantity),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (line.optionsLabel.isNotBlank()) {
                Text(
                    text = line.optionsLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalMahallaColors.current.fgMuted,
                )
            }
        }
        Text(
            text = MoneyFormatter.withCurrency(line.totalSum, currency),
            style = MaterialTheme.typography.bodyLarge.merge(TabularNums),
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun TotalsCard(order: Order, currency: String, modifier: Modifier = Modifier) {
    val mahalla = LocalMahallaColors.current
    MahallaCard(modifier = modifier) {
        TotalsRow(
            label = stringResource(R.string.cart_subtotal),
            value = MoneyFormatter.withCurrency(order.totals.subtotalSum, currency),
            color = mahalla.fgMuted,
        )
        if (order.totals.hasDiscount) {
            TotalsRow(
                label = stringResource(R.string.cart_discount),
                value = "−" + MoneyFormatter.withCurrency(order.totals.discountSum, currency),
                color = mahalla.success,
            )
        }
        if (order.totals.deliverySum > 0) {
            TotalsRow(
                label = stringResource(R.string.checkout_delivery_fee),
                value = MoneyFormatter.withCurrency(order.totals.deliverySum, currency),
                color = mahalla.fgMuted,
            )
        }
        TotalsRow(
            label = stringResource(R.string.cart_total),
            value = MoneyFormatter.withCurrency(order.totals.totalSum, currency),
            color = MaterialTheme.colorScheme.onSurface,
            emphasized = true,
        )
    }
}

@Composable
private fun TotalsRow(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    val style = if (emphasized) {
        MaterialTheme.typography.titleMedium
    } else {
        MaterialTheme.typography.bodyMedium
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = style, color = color)
        Text(text = value, style = style.merge(TabularNums), color = color)
    }
}

/** Подпись статуса. Домен знает состояние, ресурсы — формулировку. */
private fun OrderStatus.labelRes(): Int = when (this) {
    OrderStatus.Created -> R.string.order_status_created
    OrderStatus.Confirmed -> R.string.order_status_confirmed_food
    OrderStatus.Preparing -> R.string.order_status_preparing
    OrderStatus.Delivering -> R.string.order_status_delivering
    OrderStatus.ReadyForPickup -> R.string.order_status_ready
    OrderStatus.Completed -> R.string.order_status_completed
    OrderStatus.Cancelled -> R.string.order_status_cancelled
    OrderStatus.Unknown -> R.string.order_status_unknown
}

private fun OrderStatus.tone(): MahallaTone = when (this) {
    OrderStatus.Completed -> MahallaTone.Success
    OrderStatus.Cancelled -> MahallaTone.Error
    OrderStatus.Unknown -> MahallaTone.Neutral
    else -> MahallaTone.Info
}
