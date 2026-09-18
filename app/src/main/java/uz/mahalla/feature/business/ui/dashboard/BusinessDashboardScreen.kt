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
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.format.MoneyFormatter
import uz.mahalla.core.ui.components.CardSkeleton
import uz.mahalla.core.ui.components.EmptyState
import uz.mahalla.core.ui.components.ListSkeleton
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaListItem
import uz.mahalla.core.ui.components.MahallaPullToRefresh
import uz.mahalla.core.ui.components.MahallaSwitchRow
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.SectionHeader
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.business.domain.BusinessAccess
import uz.mahalla.feature.business.domain.BusinessDashboard
import uz.mahalla.feature.business.domain.BusinessMetric
import uz.mahalla.feature.business.domain.BusinessMetricKind
import uz.mahalla.feature.business.domain.BusinessSection
import uz.mahalla.feature.business.ui.BusinessInlineFailure
import uz.mahalla.feature.discovery.domain.PlaceCategory
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
    onOpenOrders: (String, String) -> Unit,
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
                    onOpenOrders(effect.placeId, effect.placeName)

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
    when (val metrics = state.metrics) {
        is ScreenState.Loading -> item(key = "metrics-loading") { CardSkeleton() }

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

        is ScreenState.Content -> item(key = "metrics") {
            MahallaCard {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
                    metrics.data.metrics.forEach { metric -> MetricRow(metric = metric) }
                }
            }
        }
    }
}

/**
 * Строка метрики: подпись слева, число справа.
 *
 * Подпись берётся из ресурсов только у знакомых ключей — состав словаря задаёт
 * сервер, и перевести то, чего приложение не видело, нечем. Незнакомый ключ
 * показывается словами (`BusinessMetric.fallbackLabel`), а не прячется:
 * метрика, которой бэкенд научился раньше приложения, всё равно полезна.
 */
@Composable
private fun MetricRow(metric: BusinessMetric, modifier: Modifier = Modifier) {
    val colors = LocalMahallaColors.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.item),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = metric.labelRes()?.let { stringResource(it) } ?: metric.fallbackLabel,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.fgMuted,
        )
        Text(
            text = when (metric.kind) {
                BusinessMetricKind.Money -> MoneyFormatter.withCurrency(
                    metric.value,
                    stringResource(R.string.currency_uzs),
                )

                BusinessMetricKind.Count -> MoneyFormatter.amount(metric.value)
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Знакомые ключи аналитики.
 *
 * Список короткий намеренно: имён схема не описывает вовсе
 * (`additionalProperties`), и это **догадки по самым вероятным написаниям**,
 * а не контракт. Промах стоит английской подписи вместо переведённой —
 * метрика при этом остаётся на экране. Как только придёт живой ответ, список
 * сверяется и правится вместе с `docs/API-CONTRACT.md`.
 */
@StringRes
private fun BusinessMetric.labelRes(): Int? = when (key.lowercase()) {
    "orders", "totalorders", "total_orders", "orderscount", "orders_count" ->
        R.string.business_metric_orders

    "revenue", "totalrevenue", "total_revenue" -> R.string.business_metric_revenue
    "views", "totalviews", "total_views" -> R.string.business_metric_views
    "reviews", "reviewscount", "reviews_count" -> R.string.business_metric_reviews
    else -> null
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
                        metrics = listOf(
                            BusinessMetric("orders", 42),
                            BusinessMetric("revenue", 4_850_000),
                            BusinessMetric("newClients", 7),
                        ),
                    ),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
