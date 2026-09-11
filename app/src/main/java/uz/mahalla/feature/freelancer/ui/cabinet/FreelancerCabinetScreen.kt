package uz.mahalla.feature.freelancer.ui.cabinet

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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.Person
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
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.EmptyState
import uz.mahalla.core.ui.components.ListSkeleton
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaDialog
import uz.mahalla.core.ui.components.MahallaIconButton
import uz.mahalla.core.ui.components.MahallaPullToRefresh
import uz.mahalla.core.ui.components.MahallaSwitchRow
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.SectionHeader
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.booking.ui.InlineFailure
import uz.mahalla.feature.freelancer.domain.Freelancer
import uz.mahalla.feature.freelancer.domain.FreelancerCabinetService
import uz.mahalla.feature.freelancer.domain.FreelancerOrder
import uz.mahalla.feature.freelancer.domain.FreelancerOrderStatus
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums

/**
 * Кабинет мастера (issue #190): анкета, переключатель «принимаю заказы»,
 * свои услуги, входящие заказы — один экран, как описано в задаче.
 *
 * Без анкеты ([ScreenState.Empty] у [FreelancerCabinetState.profile]) экран
 * показывает «стать мастером», а не ошибку — риск issue #190 в том, что
 * бэкенд может ответить и `404`, и `200` с пустым `data`, и оба случая
 * репозиторий сводит к этому состоянию.
 */
@Composable
fun FreelancerCabinetScreen(
    onOpenAnketaForm: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FreelancerCabinetViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                FreelancerCabinetEffect.OpenAnketaForm -> onOpenAnketaForm()
            }
        }
    }

    // Анкету могли поправить в другой сессии, клиент — сменить статус заказа:
    // показанное час назад после возврата ничего не стоит.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(FreelancerCabinetEvent.ScreenResumed)
    }

    FreelancerCabinetContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )

    state.serviceSheet?.let { sheet ->
        FreelancerServiceSheet(state = sheet, onEvent = viewModel::onEvent)
    }

    state.confirmDeleteService?.let { service ->
        MahallaDialog(
            title = stringResource(R.string.freelancer_cabinet_service_delete_title),
            text = stringResource(
                R.string.freelancer_cabinet_service_delete_message,
                service.title.ifBlank { stringResource(R.string.freelancer_service_unnamed) },
            ),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { viewModel.onEvent(FreelancerCabinetEvent.ServiceDeleteConfirmed) },
            onDismiss = { viewModel.onEvent(FreelancerCabinetEvent.ServiceDeleteDismissed) },
            destructive = true,
        )
    }
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun FreelancerCabinetContent(
    state: FreelancerCabinetState,
    onEvent: (FreelancerCabinetEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(title = stringResource(R.string.freelancer_cabinet_title), onBack = onBack)
        MahallaPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(FreelancerCabinetEvent.Refreshed) },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.gap),
            ) {
                cabinetItems(state = state, onEvent = onEvent)
            }
        }
    }
}

/**
 * Состояния разложены руками, а не через `ScreenStateHost` (то же решение,
 * что в «моих заведениях», issue #94): вложенная прокрутка `ApiErrorState`
 * внутри `LazyColumn` мерялась бы бесконечной высотой.
 */
private fun LazyListScope.cabinetItems(
    state: FreelancerCabinetState,
    onEvent: (FreelancerCabinetEvent) -> Unit,
) {
    when (val profile = state.profile) {
        is ScreenState.Loading -> item(key = "loading") {
            ListSkeleton(itemCount = LIST_SKELETONS)
        }

        is ScreenState.Empty -> item(key = "become-master") {
            EmptyState(
                title = stringResource(R.string.freelancer_cabinet_become_title),
                description = stringResource(R.string.freelancer_cabinet_become_description),
                icon = Icons.Outlined.Person,
                actionLabel = stringResource(R.string.freelancer_cabinet_become_action),
                onAction = { onEvent(FreelancerCabinetEvent.BecomeMasterClicked) },
            )
        }

        is ScreenState.Error -> item(key = "error") {
            InlineFailure(
                failure = profile.failure,
                onRetry = { onEvent(FreelancerCabinetEvent.Retry) },
            )
        }

        is ScreenState.Content -> {
            item(key = "profile") {
                ProfileCard(freelancer = profile.data, onEvent = onEvent)
            }
            item(key = "availability") {
                AvailabilitySection(freelancer = profile.data, state = state, onEvent = onEvent)
            }
            item(key = "services-header") {
                SectionHeader(
                    title = stringResource(R.string.freelancer_cabinet_services_title),
                    actionLabel = stringResource(R.string.freelancer_cabinet_service_add),
                    onAction = { onEvent(FreelancerCabinetEvent.ServiceAddClicked) },
                )
            }
            servicesItems(state = state, onEvent = onEvent)
            item(key = "orders-header") {
                SectionHeader(title = stringResource(R.string.freelancer_cabinet_orders_title))
            }
            ordersItems(state = state, onEvent = onEvent)
        }
    }
}

@Composable
private fun ProfileCard(
    freelancer: Freelancer,
    onEvent: (FreelancerCabinetEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMahallaColors.current
    MahallaCard(modifier = modifier) {
        Text(
            text = freelancer.name.ifBlank { stringResource(R.string.freelancer_unnamed) },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        freelancer.profession?.let { profession ->
            Text(
                text = profession,
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fgMuted,
            )
        }
        freelancer.bio?.let { bio ->
            Text(
                text = bio,
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fgMuted,
            )
        }
        freelancer.hourlyRateSum.takeIf { it > 0 }?.let { rate ->
            Text(
                text = stringResource(
                    R.string.freelancer_rate,
                    MoneyFormatter.withCurrency(rate, stringResource(R.string.currency_uzs)),
                ),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
                color = colors.fgMuted,
            )
        }
        MahallaButton(
            text = stringResource(R.string.freelancer_cabinet_edit_action),
            onClick = { onEvent(FreelancerCabinetEvent.EditAnketaClicked) },
            modifier = Modifier.padding(top = Spacing.item),
            variant = MahallaButtonVariant.Secondary,
            fillWidth = false,
        )
    }
}

@Composable
private fun AvailabilitySection(
    freelancer: Freelancer,
    state: FreelancerCabinetState,
    onEvent: (FreelancerCabinetEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        MahallaSwitchRow(
            title = stringResource(R.string.freelancer_cabinet_available_title),
            checked = freelancer.isAvailable,
            onCheckedChange = { onEvent(FreelancerCabinetEvent.AvailabilityToggled) },
            description = stringResource(R.string.freelancer_cabinet_available_description),
            enabled = !state.togglingAvailability,
        )
        state.availabilityFailure?.let { failure ->
            InlineFailure(failure = failure, modifier = Modifier.padding(top = Spacing.item))
        }
    }
}

private fun LazyListScope.servicesItems(
    state: FreelancerCabinetState,
    onEvent: (FreelancerCabinetEvent) -> Unit,
) {
    state.servicesActionFailure?.let { failure ->
        item(key = "services-action-failure") {
            InlineFailure(failure = failure)
        }
    }
    when (val services = state.services) {
        is ScreenState.Loading -> item(key = "services-loading") {
            ListSkeleton(itemCount = LIST_SKELETONS)
        }

        is ScreenState.Empty -> item(key = "services-empty") {
            EmptyState(
                title = stringResource(R.string.freelancer_cabinet_services_empty),
                icon = Icons.Outlined.Handyman,
            )
        }

        is ScreenState.Error -> item(key = "services-error") {
            InlineFailure(failure = services.failure)
        }

        is ScreenState.Content -> items(services.data, key = { "service-${it.id}" }) { service ->
            ServiceCard(
                service = service,
                pending = state.pendingServiceId == service.id,
                enabled = state.pendingServiceId == null,
                onEvent = onEvent,
            )
        }
    }
}

@Composable
private fun ServiceCard(
    service: FreelancerCabinetService,
    pending: Boolean,
    enabled: Boolean,
    onEvent: (FreelancerCabinetEvent) -> Unit,
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
                text = service.title.ifBlank { stringResource(R.string.freelancer_service_unnamed) },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!service.isActive) {
                MahallaBadge(
                    text = stringResource(R.string.freelancer_cabinet_service_inactive),
                    tone = MahallaTone.Neutral,
                )
            }
        }

        Text(
            text = MoneyFormatter.withCurrency(service.priceSum, stringResource(R.string.currency_uzs)),
            modifier = Modifier.padding(top = Spacing.item),
            style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
            color = colors.fgMuted,
        )

        service.description?.let { description ->
            Text(
                text = description,
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgMuted,
            )
        }

        Row(
            modifier = Modifier.padding(top = Spacing.item),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item),
        ) {
            MahallaIconButton(
                icon = Icons.Outlined.Edit,
                contentDescription = stringResource(R.string.freelancer_cabinet_service_edit),
                onClick = { onEvent(FreelancerCabinetEvent.ServiceEditClicked(service)) },
                enabled = enabled,
            )
            MahallaIconButton(
                icon = Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.freelancer_cabinet_service_delete),
                onClick = { onEvent(FreelancerCabinetEvent.ServiceDeleteRequested(service)) },
                enabled = enabled && !pending,
            )
        }
    }
}

private fun LazyListScope.ordersItems(
    state: FreelancerCabinetState,
    onEvent: (FreelancerCabinetEvent) -> Unit,
) {
    state.ordersActionFailure?.let { failure ->
        item(key = "orders-action-failure") { InlineFailure(failure = failure) }
    }
    when (val orders = state.orders) {
        is ScreenState.Loading -> item(key = "orders-loading") {
            ListSkeleton(itemCount = LIST_SKELETONS)
        }

        is ScreenState.Empty -> item(key = "orders-empty") {
            EmptyState(
                title = stringResource(R.string.freelancer_cabinet_orders_empty),
                icon = Icons.Outlined.Handyman,
            )
        }

        is ScreenState.Error -> item(key = "orders-error") {
            InlineFailure(failure = orders.failure, onRetry = { onEvent(FreelancerCabinetEvent.OrdersRetry) })
        }

        is ScreenState.Content -> {
            items(orders.data, key = { "order-${it.id}" }) { order ->
                IncomingOrderCard(
                    order = order,
                    pending = state.pendingOrderId == order.id,
                    enabled = state.pendingOrderId == null,
                    onEvent = onEvent,
                )
            }
            if (state.hasMoreOrders || state.loadMoreOrdersFailure != null) {
                item(key = "orders-load-more") {
                    OrdersLoadMoreItem(state = state, itemCount = orders.data.size, onEvent = onEvent)
                }
            }
        }
    }
}

@Composable
private fun IncomingOrderCard(
    order: FreelancerOrder,
    pending: Boolean,
    enabled: Boolean,
    onEvent: (FreelancerCabinetEvent) -> Unit,
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
                text = order.serviceTitle ?: stringResource(R.string.freelancer_service_unnamed),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            MahallaBadge(text = stringResource(order.status.labelRes()), tone = order.status.tone())
        }

        Text(
            text = order.scheduledAt?.let { DateTimeFormatters.dateTime(it) }
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

        val orderId = order.id
        if (orderId.isNotBlank()) {
            when (order.status) {
                FreelancerOrderStatus.Pending -> Row(
                    modifier = Modifier.padding(top = Spacing.item),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.item),
                ) {
                    MahallaButton(
                        text = stringResource(R.string.freelancer_cabinet_order_accept),
                        onClick = {
                            onEvent(
                                FreelancerCabinetEvent.OrderStatusChanged(orderId, FreelancerOrderStatus.Accepted),
                            )
                        },
                        variant = MahallaButtonVariant.Primary,
                        state = ButtonState(enabled = enabled, loading = pending),
                        fillWidth = false,
                    )
                    MahallaButton(
                        text = stringResource(R.string.freelancer_cabinet_order_reject),
                        onClick = {
                            onEvent(
                                FreelancerCabinetEvent.OrderStatusChanged(orderId, FreelancerOrderStatus.Rejected),
                            )
                        },
                        variant = MahallaButtonVariant.Ghost,
                        state = ButtonState(enabled = enabled, loading = pending),
                        fillWidth = false,
                    )
                }

                FreelancerOrderStatus.Accepted -> MahallaButton(
                    text = stringResource(R.string.freelancer_cabinet_order_complete),
                    onClick = {
                        onEvent(
                            FreelancerCabinetEvent.OrderStatusChanged(orderId, FreelancerOrderStatus.Completed),
                        )
                    },
                    modifier = Modifier.padding(top = Spacing.item),
                    variant = MahallaButtonVariant.Secondary,
                    state = ButtonState(enabled = enabled, loading = pending),
                    fillWidth = false,
                )

                FreelancerOrderStatus.Rejected,
                FreelancerOrderStatus.Completed,
                FreelancerOrderStatus.Unknown,
                -> Unit
            }
        }
    }
}

@Composable
private fun OrdersLoadMoreItem(
    state: FreelancerCabinetState,
    itemCount: Int,
    onEvent: (FreelancerCabinetEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val failure = state.loadMoreOrdersFailure
    if (failure != null) {
        InlineFailure(
            failure = failure,
            onRetry = { onEvent(FreelancerCabinetEvent.LoadMoreOrders) },
            modifier = modifier,
        )
        return
    }

    LaunchedEffect(itemCount) { onEvent(FreelancerCabinetEvent.LoadMoreOrders) }
    Box(
        modifier = modifier.fillMaxWidth().padding(Spacing.gap),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(LOAD_MORE_INDICATOR))
    }
}

/** Те же подписи и тона, что у клиентского списка заказов (issue #107). */
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

private const val LIST_SKELETONS = 2
private val LOAD_MORE_INDICATOR = 24.dp

@ThemeLanguagePreviews
@Composable
private fun FreelancerCabinetBecomeMasterPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        FreelancerCabinetContent(
            state = FreelancerCabinetState(profile = ScreenState.Empty),
            onEvent = {},
            onBack = {},
        )
    }
}

@ThemeLanguagePreviews
@Composable
private fun FreelancerCabinetContentPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        FreelancerCabinetContent(
            state = FreelancerCabinetState(
                profile = ScreenState.Content(
                    Freelancer(
                        id = "f-1",
                        name = "Aziz Karimov",
                        profession = "Santexnik",
                        bio = "Quvurlar, isitish, avariya chaqiruvi.",
                        hourlyRateSum = 50_000,
                        isAvailable = true,
                    ),
                ),
                services = ScreenState.Content(
                    listOf(
                        FreelancerCabinetService(
                            id = "s-1",
                            title = "Kran almashtirish",
                            priceSum = 80_000,
                            durationMinutes = 60,
                        ),
                        FreelancerCabinetService(
                            id = "s-2",
                            title = "Rozetka o'rnatish",
                            priceSum = 40_000,
                            durationMinutes = 30,
                            isActive = false,
                        ),
                    ),
                ),
                orders = ScreenState.Content(
                    listOf(
                        FreelancerOrder(
                            id = "o-1",
                            serviceTitle = "Kran almashtirish",
                            priceSum = 80_000,
                            status = FreelancerOrderStatus.Pending,
                        ),
                    ),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
