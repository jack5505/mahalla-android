package uz.mahalla.feature.cinema.ui.tickets

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
import androidx.compose.material.icons.outlined.ConfirmationNumber
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
import uz.mahalla.feature.cinema.domain.CinemaTicket
import uz.mahalla.feature.cinema.domain.CinemaTicketStatus
import uz.mahalla.feature.cinema.ui.CinemaFailure
import uz.mahalla.feature.cinema.ui.labelRes
import uz.mahalla.feature.cinema.ui.movie.TicketFacts
import uz.mahalla.feature.cinema.ui.tone
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import java.time.Instant

/**
 * «Мои билеты» (issue #106): код билета, место и возврат с подтверждением.
 */
@Composable
fun MyTicketsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MyTicketsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Кинотеатр мог отметить билет использованным, пока приложение было в фоне.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(MyTicketsEvent.ScreenResumed)
    }

    MyTicketsContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun MyTicketsContent(
    state: MyTicketsState,
    onEvent: (MyTicketsEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(title = stringResource(R.string.my_tickets_title), onBack = onBack)
        MahallaPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(MyTicketsEvent.Refreshed) },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.gap),
            ) {
                // Отказ возврата — над списком, а не вместо него: билеты уже на
                // экране, и прятать их из-за неудавшейся кнопки незачем.
                state.cancelFailure?.let { failure ->
                    item(key = "cancel-failure") { CinemaFailure(failure = failure) }
                }
                ticketItems(state = state, onEvent = onEvent)
            }
        }
    }

    state.confirmCancel?.let {
        MahallaDialog(
            title = stringResource(R.string.my_tickets_cancel_title),
            text = stringResource(R.string.my_tickets_cancel_message),
            confirmLabel = stringResource(R.string.my_tickets_cancel),
            onConfirm = { onEvent(MyTicketsEvent.CancelConfirmed) },
            onDismiss = { onEvent(MyTicketsEvent.CancelDismissed) },
            dismissLabel = stringResource(R.string.my_tickets_cancel_keep),
            destructive = true,
        )
    }
}

/**
 * Состояния разложены руками, а не через `ScreenStateHost`: тот рисует
 * `ApiErrorState` с собственной прокруткой, а внутри `LazyColumn` вложенная
 * прокрутка меряется бесконечной высотой и роняет измерение (issue #62).
 */
private fun LazyListScope.ticketItems(
    state: MyTicketsState,
    onEvent: (MyTicketsEvent) -> Unit,
) {
    when (val tickets = state.tickets) {
        is ScreenState.Loading -> item(key = "loading") {
            ListSkeleton(itemCount = LIST_SKELETONS)
        }

        is ScreenState.Empty -> item(key = "empty") {
            EmptyState(
                title = stringResource(R.string.my_tickets_empty_title),
                description = stringResource(R.string.my_tickets_empty_description),
                icon = Icons.Outlined.ConfirmationNumber,
            )
        }

        is ScreenState.Error -> item(key = "error") {
            CinemaFailure(
                failure = tickets.failure,
                onRetry = { onEvent(MyTicketsEvent.Retry) },
            )
        }

        is ScreenState.Content -> {
            items(tickets.data, key = CinemaTicket::id) { ticket ->
                TicketCard(
                    ticket = ticket,
                    pending = state.pendingCancelId == ticket.id,
                    // Пока идёт возврат по одной строке, остальные не трогаем:
                    // ответы приехали бы на список, которого уже нет.
                    enabled = state.pendingCancelId == null,
                    onEvent = onEvent,
                )
            }
            if (state.hasMore || state.loadMoreFailure != null) {
                item(key = "load-more") {
                    LoadMoreItem(
                        state = state,
                        itemCount = tickets.data.size,
                        onEvent = onEvent,
                    )
                }
            }
        }
    }
}

@Composable
private fun TicketCard(
    ticket: CinemaTicket,
    pending: Boolean,
    enabled: Boolean,
    onEvent: (MyTicketsEvent) -> Unit,
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
                // Ни фильма, ни его времени в билете нет — только сеанс,
                // место и код. Придумывать название нечем: сопоставить
                // `sessionId` с расписанием можно лишь зная кинотеатр и день,
                // а их билет тоже не содержит.
                text = stringResource(R.string.my_tickets_item_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            MahallaBadge(
                text = stringResource(ticket.status.labelRes()),
                tone = ticket.status.tone(),
            )
        }

        TicketFacts(ticket = ticket)

        ticket.createdAt?.let { bought ->
            Text(
                text = stringResource(
                    R.string.my_tickets_bought_at,
                    DateTimeFormatters.dateTime(bought),
                ),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgMuted,
            )
        }

        if (ticket.canCancel) {
            MahallaButton(
                text = stringResource(R.string.my_tickets_cancel),
                onClick = { onEvent(MyTicketsEvent.CancelRequested(ticket.id)) },
                modifier = Modifier.padding(top = Spacing.item),
                variant = MahallaButtonVariant.Destructive,
                state = ButtonState(enabled = enabled && !pending, loading = pending),
            )
        }
    }
}

/**
 * Хвост списка: догрузка следующей страницы по достижению конца. Провал
 * показывает кнопку с причиной — автотриггер по `itemCount` больше не
 * сработает, список ведь не вырос.
 */
@Composable
private fun LoadMoreItem(
    state: MyTicketsState,
    itemCount: Int,
    onEvent: (MyTicketsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val failure = state.loadMoreFailure
    if (failure != null) {
        CinemaFailure(
            failure = failure,
            onRetry = { onEvent(MyTicketsEvent.LoadMore) },
            modifier = modifier,
        )
        return
    }

    LaunchedEffect(itemCount) { onEvent(MyTicketsEvent.LoadMore) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.gap),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(LOAD_MORE_INDICATOR))
    }
}

private const val LIST_SKELETONS = 3
private val LOAD_MORE_INDICATOR = 24.dp

@ThemeLanguagePreviews
@Composable
private fun MyTicketsPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        MyTicketsContent(
            state = MyTicketsState(
                tickets = ScreenState.Content(
                    listOf(
                        CinemaTicket(
                            id = "t-1",
                            seatNumber = "C7",
                            priceSum = 45_000,
                            code = "4820 1174 9930",
                            status = CinemaTicketStatus.Active,
                            createdAt = Instant.parse("2026-09-04T09:00:00Z"),
                        ),
                        CinemaTicket(
                            id = "t-2",
                            priceSum = 40_000,
                            code = "1120 8845 0071",
                            status = CinemaTicketStatus.Used,
                        ),
                    ),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
