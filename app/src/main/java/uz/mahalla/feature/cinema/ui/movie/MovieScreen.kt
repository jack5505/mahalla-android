package uz.mahalla.feature.cinema.ui.movie

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.format.DateTimeFormatters
import uz.mahalla.core.format.MoneyFormatter
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.CardSkeleton
import uz.mahalla.core.ui.components.EmptyState
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaBottomSheet
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaFilterChip
import uz.mahalla.core.ui.components.MahallaTextField
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.SectionHeader
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.cinema.domain.CinemaSession
import uz.mahalla.feature.cinema.domain.CinemaTicket
import uz.mahalla.feature.cinema.domain.CinemaTicketStatus
import uz.mahalla.feature.cinema.domain.Movie
import uz.mahalla.feature.cinema.domain.SeatChoice
import uz.mahalla.feature.cinema.ui.CinemaFailure
import uz.mahalla.feature.cinema.ui.MoviePoster
import uz.mahalla.feature.cinema.ui.labelRes
import uz.mahalla.feature.cinema.ui.prefersUzbekTitle
import uz.mahalla.feature.cinema.ui.tone
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Карточка фильма и покупка билета (issue #106): описание → день → сеанс →
 * шторка покупки → билет с кодом.
 */
@Composable
fun MovieScreen(
    onOpenMyTickets: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MovieViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Места разбирают и без участия приложения: остаток на сеансе мог
    // измениться, пока экран был в фоне.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(MovieEvent.ScreenResumed)
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                MovieEffect.OpenMyTickets -> onOpenMyTickets()
            }
        }
    }

    MovieContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun MovieContent(
    state: MovieState,
    onEvent: (MovieEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val movie = (state.movie as? ScreenState.Content)?.data
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(
            title = movie?.displayTitle(prefersUzbekTitle())?.takeIf { it.isNotEmpty() }
                ?: state.placeName.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.cinema_title),
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter)
                .padding(bottom = Spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.gap),
        ) {
            // Билет куплен — выбирать больше нечего: экран уступает место
            // подтверждению. Сам он при этом не уходит: молчаливый переход
            // читается как «ничего не произошло» (issue #49).
            val bought = state.bought
            if (bought != null) {
                BoughtBlock(ticket = bought, onEvent = onEvent)
                return@Column
            }

            MovieBlock(state = state, onEvent = onEvent)

            SectionHeader(title = stringResource(R.string.booking_date_title))
            DatesRow(state = state, onEvent = onEvent)

            SectionHeader(title = stringResource(R.string.cinema_sessions_title))
            SessionsBlock(state = state, onEvent = onEvent)
        }
    }

    state.purchase?.let { session ->
        PurchaseSheet(state = state, session = session, onEvent = onEvent)
    }
}

@Composable
private fun MovieBlock(
    state: MovieState,
    onEvent: (MovieEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMahallaColors.current
    when (val movie = state.movie) {
        is ScreenState.Loading -> CardSkeleton(modifier = modifier)

        is ScreenState.Error -> CinemaFailure(
            failure = movie.failure,
            onRetry = { onEvent(MovieEvent.Retry) },
            modifier = modifier,
        )

        // Пустым фильм не бывает: он либо есть в афише, либо нет — тогда
        // ViewModel отдаёт `Error(NotFound)`. Ветка нужна компилятору.
        is ScreenState.Empty -> Unit

        is ScreenState.Content -> MahallaCard(modifier = modifier) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.gap)) {
                MoviePoster()
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
                    Text(
                        text = movie.data.displayTitle(prefersUzbekTitle())
                            .takeIf { it.isNotEmpty() }
                            ?: stringResource(R.string.cinema_movie_unnamed),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    movie.data.genre?.let { genre ->
                        Text(
                            text = genre,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.fgMuted,
                        )
                    }
                    movie.data.durationMinutes?.let { minutes ->
                        Text(
                            text = pluralStringResource(
                                R.plurals.cinema_movie_duration,
                                minutes,
                                minutes,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.fgMuted,
                        )
                    }
                    movie.data.ageRating?.let { rating -> MahallaBadge(text = rating) }
                }
            }
            movie.data.description?.let { description ->
                Text(
                    text = description,
                    modifier = Modifier.padding(top = Spacing.item),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun DatesRow(
    state: MovieState,
    onEvent: (MovieEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        state.dates.forEachIndexed { index, date ->
            MahallaFilterChip(
                label = dateLabel(date = date, index = index),
                selected = date == state.selectedDate,
                onClick = { onEvent(MovieEvent.DateSelected(date)) },
            )
        }
    }
}

/**
 * Подпись дня: «Сегодня», «Завтра», дальше — «чт, 10.09». День недели берётся
 * из ресурсов, а не из `DayOfWeek.getDisplayName`: там имя зависит от локали
 * устройства, а язык приложение выбирает своё (эпик 1.5).
 */
@Composable
private fun dateLabel(date: LocalDate, index: Int): String = when (index) {
    0 -> stringResource(R.string.booking_date_today)
    1 -> stringResource(R.string.booking_date_tomorrow)
    else -> stringResource(
        R.string.booking_date_weekday,
        stringResource(date.dayOfWeek.labelRes()),
        DateTimeFormatters.dayMonth(date),
    )
}

private fun DayOfWeek.labelRes(): Int = when (this) {
    DayOfWeek.MONDAY -> R.string.weekday_short_mon
    DayOfWeek.TUESDAY -> R.string.weekday_short_tue
    DayOfWeek.WEDNESDAY -> R.string.weekday_short_wed
    DayOfWeek.THURSDAY -> R.string.weekday_short_thu
    DayOfWeek.FRIDAY -> R.string.weekday_short_fri
    DayOfWeek.SATURDAY -> R.string.weekday_short_sat
    DayOfWeek.SUNDAY -> R.string.weekday_short_sun
}

@Composable
private fun SessionsBlock(
    state: MovieState,
    onEvent: (MovieEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
        when (val sessions = state.sessions) {
            is ScreenState.Loading -> CardSkeleton()

            // Сеансов на этот день нет — обычный ответ, а не сбой: остальные
            // дни календаря на месте, и повторять запрос незачем.
            is ScreenState.Empty -> EmptyState(
                title = stringResource(R.string.cinema_sessions_empty_title),
                description = stringResource(R.string.cinema_sessions_empty_description),
                icon = Icons.Outlined.EventBusy,
            )

            is ScreenState.Error -> CinemaFailure(
                failure = sessions.failure,
                onRetry = { onEvent(MovieEvent.SessionsRetry) },
            )

            is ScreenState.Content -> sessions.data.forEach { session ->
                SessionRow(
                    session = session,
                    onClick = { onEvent(MovieEvent.SessionClicked(session.id)) },
                )
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: CinemaSession,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMahallaColors.current
    MahallaCard(modifier = modifier, onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = session.startTime?.let(DateTimeFormatters::time)
                    ?: stringResource(R.string.cinema_session_time_unknown),
                style = MaterialTheme.typography.titleMedium.merge(TabularNums),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = session.hallName.orEmpty(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fgMuted,
            )
            session.priceSum.takeIf { it > 0 }?.let { price ->
                Text(
                    text = MoneyFormatter.withCurrency(
                        price,
                        stringResource(R.string.currency_uzs),
                    ),
                    style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        // Остаток мест — единственное, что о зале известно: схемы зала бэкенд
        // не отдаёт вовсе.
        session.availableSeats?.let { seats ->
            Text(
                text = pluralStringResource(R.plurals.cinema_seats_left, seats, seats),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgMuted,
            )
        }
    }
}

/**
 * Шторка покупки: что покупают, необязательное место и кнопка.
 *
 * Шторкой, а не отдельным экраном: форма короткая, а расписание остаётся
 * видимым за ней — на вопрос «а нет ли сеанса удобнее» отвечают, глядя на
 * него (то же решение, что у пополнения кошелька в issue #93).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PurchaseSheet(
    state: MovieState,
    session: CinemaSession,
    onEvent: (MovieEvent) -> Unit,
) {
    val colors = LocalMahallaColors.current
    MahallaBottomSheet(
        onDismiss = { onEvent(MovieEvent.PurchaseDismissed) },
        title = stringResource(R.string.cinema_buy_title),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.gap),
        ) {
            Text(
                text = stringResource(
                    R.string.cinema_buy_session,
                    session.date?.let(DateTimeFormatters::date).orEmpty(),
                    session.startTime?.let(DateTimeFormatters::time).orEmpty(),
                ),
                style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
                color = MaterialTheme.colorScheme.onSurface,
            )
            session.priceSum.takeIf { it > 0 }?.let { price ->
                Text(
                    text = MoneyFormatter.withCurrency(
                        price,
                        stringResource(R.string.currency_uzs),
                    ),
                    style = MaterialTheme.typography.titleMedium.merge(TabularNums),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            MahallaTextField(
                value = state.seat.seatNumber,
                onValueChange = { onEvent(MovieEvent.SeatChanged(it)) },
                label = stringResource(R.string.cinema_buy_seat_label),
                placeholder = stringResource(R.string.cinema_buy_seat_placeholder),
                supportingText = stringResource(R.string.cinema_buy_seat_note),
                errorText = pluralStringResource(
                    R.plurals.cinema_buy_seat_too_long,
                    SeatChoice.MAX_LENGTH,
                    SeatChoice.MAX_LENGTH,
                ).takeIf { state.seat.isTooLong },
                enabled = !state.isBuying,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Done,
                ),
            )

            // Отказ остаётся в шторке рядом с набранным местом: закрыть её
            // значило бы потерять и объяснение, и выбор (issue #34).
            state.buyFailure?.let { failure -> CinemaFailure(failure = failure) }

            MahallaButton(
                text = stringResource(R.string.cinema_buy_submit),
                onClick = { onEvent(MovieEvent.BuyClicked) },
                state = ButtonState(enabled = state.canBuy, loading = state.isBuying),
            )
            Text(
                text = stringResource(R.string.cinema_buy_payment_note),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgMuted,
            )
        }
    }
}

/** Подтверждение: код билета и куда идти дальше. */
@Composable
private fun BoughtBlock(
    ticket: CinemaTicket,
    onEvent: (MovieEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMahallaColors.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.gap)) {
        MahallaCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.item),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.cinema_bought_title),
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
            Text(
                text = stringResource(R.string.cinema_bought_description),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fgMuted,
            )
        }

        MahallaButton(
            text = stringResource(R.string.my_tickets_title),
            onClick = { onEvent(MovieEvent.MyTicketsClicked) },
        )
    }
}

/**
 * Код, место и цена билета. Код — моноширинными цифрами (`tnum`, как требует
 * ТЗ): его сверяет глазами контролёр.
 */
@Composable
internal fun TicketFacts(ticket: CinemaTicket, modifier: Modifier = Modifier) {
    val colors = LocalMahallaColors.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
        ticket.code?.let { code ->
            Text(
                text = stringResource(R.string.cinema_ticket_code, code),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.titleMedium.merge(TabularNums),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = ticket.seatNumber
                ?.let { seat -> stringResource(R.string.cinema_ticket_seat, seat) }
                ?: stringResource(R.string.cinema_ticket_seat_unknown),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.fgMuted,
        )
        ticket.priceSum.takeIf { it > 0 }?.let { price ->
            Text(
                text = MoneyFormatter.withCurrency(price, stringResource(R.string.currency_uzs)),
                style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
                color = colors.fgMuted,
            )
        }
    }
}

@ThemeLanguagePreviews
@Composable
private fun MoviePreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        MovieContent(
            state = MovieState(
                placeName = "Cinema Park",
                movie = ScreenState.Content(
                    Movie(
                        id = "m-1",
                        title = "Dune",
                        titleUz = "Qum sayyorasi",
                        genre = "Fantastika",
                        durationMinutes = 155,
                        ageRating = "16+",
                        description = "Arrakis sayyorasidagi kurash haqida.",
                    ),
                ),
                dates = listOf(LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 5)),
                selectedDate = LocalDate.of(2026, 9, 4),
                sessions = ScreenState.Content(
                    listOf(
                        CinemaSession(
                            id = "s-1",
                            hallName = "1-zal",
                            date = LocalDate.of(2026, 9, 4),
                            startTime = LocalTime.of(18, 30),
                            priceSum = 45_000,
                            availableSeats = 12,
                        ),
                        CinemaSession(
                            id = "s-2",
                            hallName = "2-zal",
                            date = LocalDate.of(2026, 9, 4),
                            startTime = LocalTime.of(21, 0),
                            priceSum = 55_000,
                            availableSeats = 3,
                        ),
                    ),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@ThemeLanguagePreviews
@Composable
private fun MovieBoughtPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        MovieContent(
            state = MovieState(
                placeName = "Cinema Park",
                bought = CinemaTicket(
                    id = "t-1",
                    seatNumber = "C7",
                    priceSum = 45_000,
                    code = "4820 1174 9930",
                    status = CinemaTicketStatus.Active,
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
