package uz.mahalla.feature.business.ui.dashboard

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.format.MoneyFormatter
import uz.mahalla.core.ui.components.CardSkeleton
import uz.mahalla.core.ui.components.EmptyState
import uz.mahalla.core.ui.components.FilterChipUi
import uz.mahalla.core.ui.components.ListSkeleton
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaFilterRow
import uz.mahalla.core.ui.components.MahallaListItem
import uz.mahalla.core.ui.components.MahallaPullToRefresh
import uz.mahalla.core.ui.components.MahallaSwitchRow
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.SectionHeader
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.isLoading
import uz.mahalla.feature.business.domain.BusinessAccess
import uz.mahalla.feature.business.domain.BusinessDashboard
import uz.mahalla.feature.business.domain.BusinessItemType
import uz.mahalla.feature.business.domain.BusinessOrderStatusStat
import uz.mahalla.feature.business.domain.BusinessSection
import uz.mahalla.feature.business.domain.BusinessTopItem
import uz.mahalla.feature.business.domain.DashboardPeriod
import uz.mahalla.feature.business.ui.BusinessInlineFailure
import uz.mahalla.feature.business.ui.orders.labelRes
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.food.domain.OrderStatus
import uz.mahalla.feature.role.domain.PlaceModerationStatus
import uz.mahalla.feature.role.domain.PlaceStaffRole
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing

/**
 * Бизнес-панель заведения (задача 12.1): метрики дня, «пауза» и вход в
 * разделы.
 *
 * До этого экрана владелец видел в приложении только витрину клиента: заказы
 * приходили на кухню, а показать их было негде.
 */
@Composable
fun BusinessDashboardScreen(
    onOpenQueue: (String, String) -> Unit,
    onOpenOrders: (String, String, String) -> Unit,
    onOpenMenu: (String, String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BusinessDashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(BusinessDashboardEvent.ScreenResumed)
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is BusinessDashboardEffect.OpenQueue -> onOpenQueue(effect.placeId, effect.placeName)
                is BusinessDashboardEffect.OpenOrders ->
                    onOpenOrders(effect.placeId, effect.placeName, effect.category)

                is BusinessDashboardEffect.OpenMenu -> onOpenMenu(effect.placeId, effect.placeName)
            }
        }
    }

    BusinessDashboardContentScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun BusinessDashboardContentScreen(
    state: BusinessDashboardState,
    onEvent: (BusinessDashboardEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(
            title = state.title.takeIf(String::isNotBlank)
                ?: stringResource(R.string.business_title),
            onBack = onBack,
        )
        MahallaPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(BusinessDashboardEvent.Refreshed) },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.gap),
            ) {
                dashboardItems(state = state, onEvent = onEvent)
            }
        }
    }
}

/**
 * Состояния разложены руками, а не через `ScreenStateHost`: тот рисует
 * `ApiErrorState` с собственной прокруткой, а внутри `LazyColumn` вложенная
 * прокрутка роняет измерение (issue #62).
 */
private fun LazyListScope.dashboardItems(
    state: BusinessDashboardState,
    onEvent: (BusinessDashboardEvent) -> Unit,
) {
    when (val access = state.access) {
        is ScreenState.Loading -> item(key = "access-loading") { ListSkeleton(itemCount = 3) }

        // «Доступа нет» — не пустой экран: заведение может быть чужим, а может
        // быть просто не загруженным. Причину показывает сам сервер (issue #34).
        is ScreenState.Empty -> item(key = "access-empty") {
            EmptyState(
                title = stringResource(R.string.business_no_access_title),
                description = stringResource(R.string.business_no_access_description),
            )
        }

        is ScreenState.Error -> item(key = "access-error") {
            BusinessInlineFailure(
                failure = access.failure,
                onRetry = { onEvent(BusinessDashboardEvent.Retry) },
            )
        }

        is ScreenState.Content -> {
            accessItems(access = access.data, state = state, onEvent = onEvent)
        }
    }
}

private fun LazyListScope.accessItems(
    access: BusinessAccess,
    state: BusinessDashboardState,
    onEvent: (BusinessDashboardEvent) -> Unit,
) {
    // Заявка на модерации объясняет себя словами: панель без разделов и без
    // объяснения читается как сломанная.
    if (!access.isOperational) {
        item(key = "not-operational") {
            EmptyState(
                title = stringResource(R.string.business_pending_title),
                description = stringResource(R.string.business_pending_description),
                icon = Icons.Outlined.Insights,
            )
        }
    }

    item(key = "metrics-header") {
        SectionHeader(title = stringResource(R.string.business_metrics_title))
    }
    metricItems(state = state, onEvent = onEvent)

    if (access.canPause) {
        item(key = "pause") {
            MahallaCard {
                MahallaSwitchRow(
                    title = stringResource(R.string.business_pause_title),
                    checked = access.isAvailable,
                    onCheckedChange = { onEvent(BusinessDashboardEvent.PauseToggled) },
                    description = stringResource(R.string.business_pause_description),
                    enabled = !state.pauseInProgress,
                )
            }
        }
    }

    state.actionFailure?.let { failure ->
        item(key = "action-failure") { BusinessInlineFailure(failure = failure) }
    }

    if (access.sections.isNotEmpty()) {
        item(key = "sections-header") {
            SectionHeader(title = stringResource(R.string.business_sections_title))
        }
        items(access.sections, key = { it.name }) { section ->
            MahallaListItem(
                title = stringResource(section.labelRes()),
                subtitle = stringResource(section.descriptionRes()),
                leadingIcon = section.icon(),
                // Раздел неопубликованного заведения не открывается: заказов и
                // очереди у него быть не может, а пустой экран за нажатием
                // читается как ошибка.
                onClick = if (access.canOpen(section)) {
                    { onEvent(BusinessDashboardEvent.SectionClicked(section)) }
                } else {
                    null
                },
            )
        }
    } else if (access.isOperational) {
        // Категория без панели — не ошибка: у аптеки и кинотеатра бизнес-ручек
        // у бэкенда попросту нет.
        item(key = "no-sections") {
            EmptyState(
                title = stringResource(R.string.business_no_sections_title),
                description = stringResource(R.string.business_no_sections_description),
            )
        }
    }
}

private fun LazyListScope.metricItems(
    state: BusinessDashboardState,
    onEvent: (BusinessDashboardEvent) -> Unit,
) {
    item(key = "period-switcher") {
        MahallaFilterRow(
            items = DashboardPeriod.entries.map { period ->
                FilterChipUi(id = period.name, label = stringResource(period.labelRes()))
            },
            selectedId = state.period.name,
            onSelect = { id ->
                DashboardPeriod.entries.firstOrNull { it.name == id }?.let { period ->
                    onEvent(BusinessDashboardEvent.PeriodSelected(period))
                }
            },
            // Пока метрики грузятся, переключатель недоступен: иначе тап
            // молча потеряется (ViewModel отбрасывает событие по тому же
            // флагу), и человек решит, что ничего не нажалось.
            enabled = !state.metrics.isLoading,
        )
    }

    when (val metrics = state.metrics) {
        is ScreenState.Loading -> item(key = "metrics-loading") { CardSkeleton() }

        // Период без продаж — не ошибка: у нового заведения «День» пуст, пока
        // «Месяц» уже не пуст. Переключатель при этом остаётся на месте.
        is ScreenState.Empty -> item(key = "metrics-empty") {
            Text(
                text = stringResource(R.string.business_metrics_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMahallaColors.current.fgMuted,
            )
        }

        // Отказ аналитики не прячет разделы: они грузятся другой ручкой.
        is ScreenState.Error -> item(key = "metrics-error") {
            BusinessInlineFailure(
                failure = metrics.failure,
                onRetry = { onEvent(BusinessDashboardEvent.RetryMetrics) },
            )
        }

        is ScreenState.Content -> dashboardContentItems(dashboard = metrics.data)
    }
}

private fun LazyListScope.dashboardContentItems(dashboard: BusinessDashboard) {
    item(key = "metrics-summary") {
        MahallaCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
                SummaryRow(
                    label = stringResource(R.string.business_metric_revenue),
                    value = MoneyFormatter.withCurrency(
                        dashboard.revenueSum,
                        stringResource(R.string.currency_uzs),
                    ),
                )
                SummaryRow(
                    label = stringResource(R.string.business_metric_orders),
                    value = MoneyFormatter.amount(dashboard.totalOrders),
                )
            }
        }
    }

    if (dashboard.ordersByStatus.isNotEmpty()) {
        item(key = "orders-by-status-header") {
            SectionHeader(title = stringResource(R.string.business_dashboard_orders_by_status_title))
        }
        // Индекс в ключе — страховка: сервер группирует по статусу и дублей не
        // шлёт, но два незнакомых статуса схлопнутся в один и тот же
        // `OrderStatus.Unknown`, и без индекса список упал бы на дублях ключа.
        itemsIndexed(
            dashboard.ordersByStatus,
            key = { index, stat -> "status-$index-${stat.status.name}" },
        ) { _, stat -> OrderStatusStatRow(stat = stat) }
    }

    if (dashboard.topItems.isNotEmpty()) {
        item(key = "top-items-header") {
            SectionHeader(title = stringResource(R.string.business_dashboard_top_items_title))
        }
        itemsIndexed(
            dashboard.topItems,
            key = { index, item -> "$index-${item.itemId.takeIf(String::isNotEmpty) ?: item.name}" },
        ) { _, topItem -> TopItemRow(topItem = topItem) }
    }
}

/** Строка сводки: подпись слева, число справа (выручка, число заказов). */
@Composable
private fun SummaryRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.item),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalMahallaColors.current.fgMuted,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun OrderStatusStatRow(stat: BusinessOrderStatusStat, modifier: Modifier = Modifier) {
    MahallaListItem(
        modifier = modifier,
        title = stringResource(stat.status.labelRes()),
        subtitle = stringResource(
            R.string.business_dashboard_status_orders_count,
            stat.orderCount,
        ),
        trailingText = MoneyFormatter.withCurrency(
            stat.totalAmountSum,
            stringResource(R.string.currency_uzs),
        ),
        showChevron = false,
    )
}

@Composable
private fun TopItemRow(topItem: BusinessTopItem, modifier: Modifier = Modifier) {
    MahallaListItem(
        modifier = modifier,
        title = topItem.name,
        subtitle = pluralStringResource(
            R.plurals.business_dashboard_top_item_quantity,
            topItem.quantity.toInt(),
            topItem.quantity,
        ),
        trailingText = MoneyFormatter.withCurrency(
            topItem.totalAmountSum,
            stringResource(R.string.currency_uzs),
        ),
        showChevron = false,
    )
}

@StringRes
private fun DashboardPeriod.labelRes(): Int = when (this) {
    DashboardPeriod.Day -> R.string.business_dashboard_period_day
    DashboardPeriod.Week -> R.string.business_dashboard_period_week
    DashboardPeriod.Month -> R.string.business_dashboard_period_month
}

@StringRes
private fun BusinessSection.labelRes(): Int = when (this) {
    BusinessSection.Queue -> R.string.business_section_queue
    BusinessSection.Orders -> R.string.business_section_orders
    BusinessSection.Menu -> R.string.business_section_menu
}

@StringRes
private fun BusinessSection.descriptionRes(): Int = when (this) {
    BusinessSection.Queue -> R.string.business_section_queue_description
    BusinessSection.Orders -> R.string.business_section_orders_description
    BusinessSection.Menu -> R.string.business_section_menu_description
}

private fun BusinessSection.icon(): ImageVector = when (this) {
    BusinessSection.Queue -> Icons.Outlined.People
    BusinessSection.Orders -> Icons.Outlined.ReceiptLong
    BusinessSection.Menu -> Icons.Outlined.MenuBook
}

@ThemeLanguagePreviews
@Composable
private fun BusinessDashboardScreenPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        BusinessDashboardContentScreen(
            state = BusinessDashboardState(
                placeName = "Osh Markazi",
                access = ScreenState.Content(
                    BusinessAccess(
                        placeId = "p-1",
                        placeName = "Osh Markazi",
                        category = PlaceCategory.Food,
                        role = PlaceStaffRole.Owner,
                        status = PlaceModerationStatus.Active,
                        isAvailable = true,
                    ),
                ),
                metrics = ScreenState.Content(
                    BusinessDashboard(
                        period = DashboardPeriod.Day,
                        revenueSum = 4_850_000,
                        totalOrders = 42,
                        ordersByStatus = listOf(
                            BusinessOrderStatusStat(
                                status = OrderStatus.Completed,
                                orderCount = 38,
                                totalAmountSum = 4_600_000,
                            ),
                            BusinessOrderStatusStat(
                                status = OrderStatus.Cancelled,
                                orderCount = 4,
                                totalAmountSum = 250_000,
                            ),
                        ),
                        topItems = listOf(
                            BusinessTopItem(
                                itemId = "i-1",
                                itemType = BusinessItemType.MenuItem,
                                name = "Osh",
                                quantity = 21,
                                totalAmountSum = 2_100_000,
                            ),
                        ),
                    ),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
