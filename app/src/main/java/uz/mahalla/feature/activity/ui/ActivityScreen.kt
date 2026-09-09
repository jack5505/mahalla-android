package uz.mahalla.feature.activity.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
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
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.components.EmptyState
import uz.mahalla.core.ui.components.ListSkeleton
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaErrorDetails
import uz.mahalla.core.ui.components.MahallaPullToRefresh
import uz.mahalla.core.ui.components.MahallaSegmentedControl
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.OrderCard
import uz.mahalla.core.ui.components.OrderCardUi
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.userMessage
import uz.mahalla.feature.activity.domain.Activity
import uz.mahalla.feature.activity.domain.ActivityFilter
import uz.mahalla.feature.activity.domain.ActivityKind
import uz.mahalla.feature.activity.domain.ActivitySource
import uz.mahalla.feature.activity.domain.ActivityStatus
import uz.mahalla.feature.activity.domain.ActivityTarget
import uz.mahalla.ui.theme.Spacing
import java.time.Instant

/**
 * «Мои активности» (issue #73, задача T7) — таб нижней навигации, который до
 * этого был 16 строками заглушки.
 *
 * Один список из пяти источников бэкенда: заказы всех вертикалей, брони
 * игровых зон, записи к мастеру и к врачу, билеты в кино. Источники
 * подключаются по одному — отсутствующий просто не даёт элементов, и каркас
 * от этого не меняется.
 */
@Composable
fun ActivityScreen(
    onFoodOrderClick: (String) -> Unit,
    onDiscoveryClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ActivityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ActivityEffect.OpenFoodOrder -> onFoodOrderClick(effect.orderId)
                ActivityEffect.OpenDiscovery -> onDiscoveryClick()
            }
        }
    }

    // Статус заказа меняется на сервере, и таб открывают как раз затем, чтобы
    // его увидеть.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(ActivityEvent.ScreenResumed)
    }

    ActivityContentScreen(state = state, onEvent = viewModel::onEvent, modifier = modifier)
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun ActivityContentScreen(
    state: ActivityState,
    onEvent: (ActivityEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        // Кнопки «назад» нет: экран — корень таба, возвращаться некуда.
        MahallaTopBar(title = stringResource(R.string.activity_title))

        // Фильтр стоит над списком и виден всегда, даже на пустой вкладке:
        // иначе человек, у которого всё выполнено, не нашёл бы «Историю».
        val filters = ActivityFilter.entries
        MahallaSegmentedControl(
            options = filters.map { stringResource(it.labelRes()) },
            selectedIndex = filters.indexOf(state.filter),
            onSelect = { index -> onEvent(ActivityEvent.FilterSelected(filters[index])) },
            modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.item),
        )

        MahallaPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(ActivityEvent.Refreshed) },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.gap),
            ) {
                // Частичный отказ (требование T7): список показан, а сбойные
                // разделы отмечены строкой над ним. Одна строка на источник —
                // человек должен понимать, чего именно в списке не хватает.
                state.sourceFailures.forEach { (source, failure) ->
                    item(key = "failure-${source.name}") {
                        SourceFailure(
                            source = source,
                            failure = failure,
                            onRetry = { onEvent(ActivityEvent.Retry) },
                        )
                    }
                }
                activityItems(state = state, onEvent = onEvent)
            }
        }
    }
}

/**
 * Состояния разложены руками, а не через `ScreenStateHost`: тот рисует
 * `ApiErrorState` с собственной прокруткой, а внутри `LazyColumn` вложенная
 * прокрутка меряется бесконечной высотой и роняет измерение (issue #62).
 */
private fun LazyListScope.activityItems(
    state: ActivityState,
    onEvent: (ActivityEvent) -> Unit,
) {
    when (val items = state.items) {
        is ScreenState.Loading -> item(key = "loading") {
            ListSkeleton(itemCount = LIST_SKELETONS)
        }

        // Ничего не заказывали вовсе — пустое состояние с действием, а не
        // голое «ничего не найдено» (требование T7).
        is ScreenState.Empty -> item(key = "empty") {
            EmptyState(
                title = stringResource(R.string.activity_empty_title),
                description = stringResource(R.string.activity_empty_description),
                actionLabel = stringResource(R.string.activity_empty_action),
                onAction = { onEvent(ActivityEvent.DiscoveryRequested) },
            )
        }

        // Полный отказ: не ответил ни один источник. Частичный сюда не
        // попадает — он живёт отметками разделов над списком.
        is ScreenState.Error -> item(key = "error") {
            InlineFailure(
                message = items.failure.userMessage(),
                failure = items.failure,
                onRetry = { onEvent(ActivityEvent.Retry) },
            )
        }

        is ScreenState.Content -> {
            val visible = state.visible
            if (visible.isEmpty()) {
                // Активности есть, но не в этой вкладке. Предлагать «сходите
                // на главную» тому, у кого двадцать заказов в истории, значит
                // не заметить его самого.
                item(key = "empty-tab") { EmptyTab(filter = state.filter) }
                return
            }
            items(visible, key = Activity::key) { activity ->
                ActivityRow(
                    activity = activity,
                    onClick = { onEvent(ActivityEvent.ActivityClicked(activity.key)) },
                )
            }
            if (state.hasMore || state.loadMoreFailure != null) {
                item(key = "load-more") {
                    LoadMoreItem(state = state, onEvent = onEvent)
                }
            }
        }
    }
}

/**
 * Строка списка — `OrderCard` из кита эпика 2.
 *
 * `BookingCard` и `TicketCard` из того же кита не подошли, хотя T7 их
 * называет: `BookingCardUi` требует числа гостей и отдельных даты и времени, а
 * `TicketCardUi` — номера талона и «сколько человек впереди». Ни одного из
 * этих полей ни один из пяти ответов бэкенда не содержит — они про бронь
 * столика и про очередь, которых в контракте пока нет. `OrderCardUi`
 * (заголовок, бейдж статуса, сумма, время) ложится на все пять источников без
 * выдумывания данных.
 */
@Composable
private fun ActivityRow(
    activity: Activity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kindLabel = stringResource(activity.kind.labelRes())
    OrderCard(
        order = OrderCardUi(
            id = activity.key,
            // Названия заведения бэкенд не отдаёт ни в одном из пяти ответов
            // (только `placeId`), поэтому заголовок — вид активности, а
            // уточнение (номер заказа, услуга, место в зале) идёт рядом.
            title = activity.note?.let { "$kindLabel · $it" } ?: kindLabel,
            statusLabel = stringResource(activity.status.labelRes()),
            statusTone = activity.status.tone(),
            amountLabel = activity.amount
                ?.let { MoneyFormatter.withCurrency(it, stringResource(R.string.currency_uzs)) }
                .orEmpty(),
            timeLabel = activity.occurredAt
                ?.let(DateTimeFormatters::dateTime)
                ?: stringResource(R.string.activity_no_date),
        ),
        modifier = modifier,
        // Кликабельно только то, у чего есть куда вести: нажатие без
        // последствий читается как сломанный экран. Пока это заказы «Еды» —
        // у брони, записи и билета своих экранов ещё нет.
        onClick = onClick.takeIf { activity.isActionable },
    )
}

/** Вкладка пуста, но активности есть в соседней. */
@Composable
private fun EmptyTab(filter: ActivityFilter, modifier: Modifier = Modifier) {
    val (title, description) = when (filter) {
        ActivityFilter.Active ->
            R.string.activity_empty_active_title to R.string.activity_empty_active_description

        ActivityFilter.History ->
            R.string.activity_empty_history_title to R.string.activity_empty_history_description
    }
    EmptyState(
        modifier = modifier,
        title = stringResource(title),
        description = stringResource(description),
    )
}

/**
 * Отметка сбойного раздела при частичном отказе. Названа именем раздела, а не
 * «что-то не загрузилось»: человек должен понимать, чего именно в списке не
 * хватает — иначе он будет считать, что броней у него нет.
 */
@Composable
private fun SourceFailure(
    source: ActivitySource,
    failure: ApiFailure,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    InlineFailure(
        message = stringResource(R.string.activity_source_failed, stringResource(source.labelRes())),
        failure = failure,
        onRetry = onRetry,
        modifier = modifier,
    )
}

/**
 * Отказ внутри списка: причина, подробности ответа сервера (issue #34) и
 * повтор. `ApiErrorState` здесь не годится — он прокручивается сам
 * (см. [activityItems]).
 */
@Composable
private fun InlineFailure(
    message: String,
    failure: ApiFailure,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        failure.server?.let { MahallaErrorDetails(server = it) }
        MahallaButton(
            text = stringResource(R.string.action_retry),
            onClick = onRetry,
            variant = MahallaButtonVariant.Secondary,
            fillWidth = false,
        )
    }
}

/**
 * Хвост списка: догрузка следующих страниц по достижению конца. Провал
 * показывает кнопку с причиной — иначе крутилка осталась бы навсегда, ведь
 * список не вырос и автотриггер больше не сработает.
 *
 * Триггер висит на **курсоре** [ActivityState.nextPages], а не на числе строк.
 * Так и должно быть, потому что вкладку отбирает клиент: догруженная страница
 * может целиком уехать в «историю», число строк «активных» при этом не
 * изменится — и триггер по нему не сработал бы ни разу, оставив крутилку
 * висеть при `hasMore = true`. Курсор же сдвигается после каждой удачной
 * страницы, поэтому догрузка идёт, пока источники не кончатся, и
 * останавливается сама: когда `nextPages` пуст, хвоста в списке уже нет.
 */
@Composable
private fun LoadMoreItem(
    state: ActivityState,
    onEvent: (ActivityEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val failure = state.loadMoreFailure
    if (failure != null) {
        InlineFailure(
            message = failure.userMessage(),
            failure = failure,
            onRetry = { onEvent(ActivityEvent.LoadMore) },
            modifier = modifier,
        )
        return
    }

    LaunchedEffect(state.nextPages) { onEvent(ActivityEvent.LoadMore) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.gap),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(LOAD_MORE_INDICATOR))
    }
}

private fun ActivityFilter.labelRes(): Int = when (this) {
    ActivityFilter.Active -> R.string.activity_filter_active
    ActivityFilter.History -> R.string.activity_filter_history
}

private const val LIST_SKELETONS = 4
private val LOAD_MORE_INDICATOR = 24.dp

@ThemeLanguagePreviews
@Composable
private fun ActivityScreenPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        ActivityContentScreen(
            state = ActivityState(
                items = ScreenState.Content(
                    listOf(
                        Activity(
                            id = "o-1",
                            source = ActivitySource.Orders,
                            kind = ActivityKind.FoodOrder,
                            status = ActivityStatus.InProgress,
                            occurredAt = Instant.parse("2026-09-04T08:10:00Z"),
                            amount = 84_000,
                            note = "F-2026-0042",
                            target = ActivityTarget.FoodOrder("o-1"),
                        ),
                        Activity(
                            id = "b-1",
                            source = ActivitySource.GamingBookings,
                            kind = ActivityKind.GamingBooking,
                            status = ActivityStatus.Confirmed,
                            occurredAt = Instant.parse("2026-09-05T13:00:00Z"),
                            amount = 60_000,
                        ),
                        Activity(
                            id = "a-1",
                            source = ActivitySource.MasterAppointments,
                            kind = ActivityKind.MasterAppointment,
                            status = ActivityStatus.Placed,
                            occurredAt = Instant.parse("2026-09-06T05:30:00Z"),
                            amount = 45_000,
                            note = "Soch olish",
                        ),
                    ),
                ),
                sourceFailures = mapOf(
                    ActivitySource.CinemaTickets to ApiFailure(ApiError.NoConnection),
                ),
            ),
            onEvent = {},
        )
    }
}
