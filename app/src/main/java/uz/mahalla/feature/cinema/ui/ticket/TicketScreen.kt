package uz.mahalla.feature.cinema.ui.ticket

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import uz.mahalla.core.ui.components.CardSkeleton
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaCard
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
 * Карточка билета (issue #183): открывается из «моих активностей», читает
 * актуальный статус по id, а не показывает снимок строки списка.
 */
@Composable
fun TicketScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TicketViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Кинотеатр мог отметить билет использованным, пока приложение было в фоне.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(TicketEvent.ScreenResumed)
    }

    TicketContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun TicketContent(
    state: TicketState,
    onEvent: (TicketEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(title = stringResource(R.string.my_tickets_item_title), onBack = onBack)
        MahallaPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(TicketEvent.Refreshed) },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.gutter),
            ) {
                when (val ticket = state.ticket) {
                    is ScreenState.Loading -> CardSkeleton()

                    is ScreenState.Error -> CinemaFailure(
                        failure = ticket.failure,
                        onRetry = { onEvent(TicketEvent.Retry) },
                    )

                    // Билет без id сервер бы не отдал — своего пустого
                    // состояния у карточки одной сущности нет.
                    is ScreenState.Empty -> Unit

                    is ScreenState.Content -> TicketCard(ticket.data)
                }
            }
        }
    }
}

@Composable
private fun TicketCard(ticket: CinemaTicket, modifier: Modifier = Modifier) {
    val colors = LocalMahallaColors.current
    MahallaCard(modifier = modifier) {
        // Заголовок карточки не дублирует шапку экрана («Билет в кино» уже в
        // `MahallaTopBar`) — здесь только статус, названия фильма в билете нет.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MahallaBadge(
                text = stringResource(ticket.status.labelRes()),
                tone = ticket.status.tone(),
            )
        }

        TicketFacts(ticket = ticket)

        ticket.createdAt?.let { bought ->
            Text(
                text = stringResource(R.string.my_tickets_bought_at, DateTimeFormatters.dateTime(bought)),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgMuted,
            )
        }
    }
}

@ThemeLanguagePreviews
@Composable
private fun TicketPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        TicketContent(
            state = TicketState(
                ticket = ScreenState.Content(
                    CinemaTicket(
                        id = "t-1",
                        seatNumber = "C7",
                        priceSum = 45_000,
                        code = "4820 1174 9930",
                        status = CinemaTicketStatus.Active,
                        createdAt = Instant.parse("2026-09-04T09:00:00Z"),
                    ),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
