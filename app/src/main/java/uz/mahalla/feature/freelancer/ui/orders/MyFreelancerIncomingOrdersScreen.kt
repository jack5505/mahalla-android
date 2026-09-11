package uz.mahalla.feature.freelancer.ui.orders

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
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.format.DateTimeFormatters
import uz.mahalla.core.format.MoneyFormatter
import uz.mahalla.core.ui.components.EmptyState
import uz.mahalla.core.ui.components.ListSkeleton
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaPullToRefresh
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.booking.ui.InlineFailure
import uz.mahalla.feature.freelancer.domain.FreelancerOrder
import uz.mahalla.feature.freelancer.domain.FreelancerOrderStatus
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums
import java.time.Instant

/**
 * Входящие заказы мастера (issue #190): то, что клиенты заказали у этого
 * мастера. Список тот же, что у «Моих заказов у мастеров»
 * ([MyFreelancerOrdersScreen]), но здесь заказом можно управлять — принять,
 * отклонить или отметить выполненным.
 */
@Composable
fun MyFreelancerIncomingOrdersScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MyFreelancerIncomingOrdersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Новый заказ мог прийти, пока приложение было в фоне.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(MyFreelancerIncomingOrdersEvent.ScreenResumed)
    }

    MyFreelancerIncomingOrdersContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun MyFreelancerIncomingOrdersContent(
    state: MyFreelancerIncomingOrdersState,
    onEvent: (MyFreelancerIncomingOrdersEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(
            title = stringResource(R.string.freelancer_incoming_orders_title),
            onBack = onBack,
        )
        MahallaPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(MyFreelancerIncomingOrdersEvent.Refreshed) },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.gap),
            ) {
                // Отказ последнего действия — общей строкой сверху списка, а
                // не привязан к конкретной карточке: чей это был отказ, видно
                // и так (кнопка успела вернуться в обычное состояние), а
                // баннер на каждой карточке сразу для всех был бы избыточным.
                state.actionFailure?.let { failure ->
                    item(key = "action-failure") { InlineFailure(failure = failure) }
                }
                orderItems(state = state, onEvent = onEvent)
            }
        }
    }
}

/**
 * Состояния разложены руками, а не через `ScreenStateHost`: тот рисует
 * `ApiErrorState` с собственной прокруткой, а внутри `LazyColumn` вложенная
 * прокрутка меряется бесконечной высотой и роняет измерение (issue #62,
 * то же решение, что у [MyFreelancerOrdersScreen]).
 */
private fun LazyListScope.orderItems(
    state: MyFreelancerIncomingOrdersState,
    onEvent: (MyFreelancerIncomingOrdersEvent) -> Unit,
) {
    when (val orders = state.orders) {
        is ScreenState.Loading -> item(key = "loading") {
            ListSkeleton(itemCount = LIST_SKELETONS)
        }

        is ScreenState.Empty -> item(key = "empty") {
            EmptyState(
                title = stringResource(R.string.freelancer_incoming_orders_empty_title),
                description = stringResource(R.string.freelancer_incoming_orders_empty_description),
                icon = Icons.Outlined.Handyman,
            )
        }

        is ScreenState.Error -> item(key = "error") {
            InlineFailure(
                failure = orders.failure,
                onRetry = { onEvent(MyFreelancerIncomingOrdersEvent.Retry) },
            )
        }

        is ScreenState.Content -> {
            items(orders.data, key = FreelancerOrder::id) { order ->
                OrderCard(
                    order = order,
                    pending = state.pendingOrderId == order.id,
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

@Composable
private fun OrderCard(
    order: FreelancerOrder,
    pending: Boolean,
    onEvent: (MyFreelancerIncomingOrdersEvent) -> Unit,
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
                text = order.serviceTitle
                    ?: stringResource(R.string.freelancer_service_unnamed),
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
            text = order.scheduledAt
                ?.let { DateTimeFormatters.dateTime(it) }
                ?: stringResource(R.string.freelancer_time_asap),
            modifier = Modifier.padding(top = Spacing.item),
            style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
            color = MaterialTheme.colorScheme.onSurface,
        )

        order.priceSum.takeIf { it > 0 }?.let { price ->
            Text(
                text = MoneyFormatter.withCurrency(price, stringResource(R.string.currency_uzs)),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
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

        order.comment?.let { comment ->
            Text(
                text = comment,
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgMuted,
            )
        }

        OrderActions(orderId = order.id, status = order.status, pending = pending, onEvent = onEvent)
    }
}

/**
 * Кнопки зависят от статуса: ждущий заказ можно принять или отклонить,
 * принятый — отметить выполненным, а по финальным статусам
 * ([FreelancerOrderStatus.isFinal]) действий больше нет. Пока летит смена
 * статуса ([pending]), кнопки уступают место крутилке — какая именно из двух
 * была нажата, состояние не хранит (`pendingOrderId` один на заказ), а
 * показать сразу обе загруженными было бы неправдой.
 */
@Composable
private fun OrderActions(
    orderId: String,
    status: FreelancerOrderStatus,
    pending: Boolean,
    onEvent: (MyFreelancerIncomingOrdersEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pending) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = Spacing.item),
            contentAlignment = Alignment.CenterStart,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(ACTION_INDICATOR))
        }
        return
    }

    when (status) {
        FreelancerOrderStatus.Pending -> Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = Spacing.item),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item),
        ) {
            MahallaButton(
                text = stringResource(R.string.freelancer_incoming_order_accept),
                onClick = { onEvent(MyFreelancerIncomingOrdersEvent.AcceptClicked(orderId)) },
                modifier = Modifier.weight(1f),
            )
            MahallaButton(
                text = stringResource(R.string.freelancer_incoming_order_reject),
                onClick = { onEvent(MyFreelancerIncomingOrdersEvent.RejectClicked(orderId)) },
                modifier = Modifier.weight(1f),
                variant = MahallaButtonVariant.Destructive,
            )
        }

        FreelancerOrderStatus.Accepted -> MahallaButton(
            text = stringResource(R.string.freelancer_incoming_order_complete),
            onClick = { onEvent(MyFreelancerIncomingOrdersEvent.CompleteClicked(orderId)) },
            modifier = modifier
                .fillMaxWidth()
                .padding(top = Spacing.item),
        )

        // Финальные статусы и незнакомое значение — действий нет: заказ либо
        // уже закрыт, либо его судьба видна только на сервере.
        FreelancerOrderStatus.Rejected,
        FreelancerOrderStatus.Completed,
        FreelancerOrderStatus.Unknown,
        -> Unit
    }
}

/**
 * Хвост списка: догрузка следующей страницы по достижению конца. Провал
 * показывает кнопку с причиной — автотриггер по `itemCount` больше не
 * сработает, список ведь не вырос.
 */
@Composable
private fun LoadMoreItem(
    state: MyFreelancerIncomingOrdersState,
    itemCount: Int,
    onEvent: (MyFreelancerIncomingOrdersEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val failure = state.loadMoreFailure
    if (failure != null) {
        InlineFailure(
            failure = failure,
            onRetry = { onEvent(MyFreelancerIncomingOrdersEvent.LoadMore) },
            modifier = modifier,
        )
        return
    }

    LaunchedEffect(itemCount) { onEvent(MyFreelancerIncomingOrdersEvent.LoadMore) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.gap),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(LOAD_MORE_INDICATOR))
    }
}

/** Подписи статусов: домен знает состояние, ресурсы — формулировку. */
@StringRes
private fun FreelancerOrderStatus.labelRes(): Int = when (this) {
    FreelancerOrderStatus.Pending -> R.string.freelancer_order_status_pending
    FreelancerOrderStatus.Accepted -> R.string.freelancer_order_status_accepted
    FreelancerOrderStatus.Rejected -> R.string.freelancer_order_status_rejected
    FreelancerOrderStatus.Completed -> R.string.freelancer_order_status_completed
    FreelancerOrderStatus.Unknown -> R.string.appointment_status_unknown
}

private fun FreelancerOrderStatus.tone(): MahallaTone = when (this) {
    FreelancerOrderStatus.Accepted -> MahallaTone.Success
    FreelancerOrderStatus.Pending -> MahallaTone.Info
    FreelancerOrderStatus.Completed -> MahallaTone.Neutral
    FreelancerOrderStatus.Rejected -> MahallaTone.Error
    FreelancerOrderStatus.Unknown -> MahallaTone.Neutral
}

private const val LIST_SKELETONS = 3
private val LOAD_MORE_INDICATOR = 24.dp
private val ACTION_INDICATOR = 24.dp

@ThemeLanguagePreviews
@Composable
private fun MyFreelancerIncomingOrdersPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        MyFreelancerIncomingOrdersContent(
            state = MyFreelancerIncomingOrdersState(
                orders = ScreenState.Content(
                    listOf(
                        FreelancerOrder(
                            id = "o-1",
                            serviceTitle = "Kran almashtirish",
                            priceSum = 150_000,
                            status = FreelancerOrderStatus.Pending,
                            scheduledAt = Instant.parse("2026-09-06T05:30:00Z"),
                            address = "Toshkent, Chilonzor 7",
                        ),
                        FreelancerOrder(
                            id = "o-2",
                            serviceTitle = "Rozetka o'rnatish",
                            status = FreelancerOrderStatus.Accepted,
                        ),
                        FreelancerOrder(
                            id = "o-3",
                            serviceTitle = "Deraza tozalash",
                            status = FreelancerOrderStatus.Completed,
                        ),
                    ),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
