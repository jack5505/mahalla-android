package uz.mahalla.feature.gaming.ui.zones

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SportsEsports
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.format.DateTimeFormatters
import uz.mahalla.core.format.MoneyFormatter
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.EmptyState
import uz.mahalla.core.ui.components.ListSkeleton
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaBottomSheet
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaErrorDetails
import uz.mahalla.core.ui.components.MahallaFilterChip
import uz.mahalla.core.ui.components.MahallaPullToRefresh
import uz.mahalla.core.ui.components.MahallaQuantityStepper
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.userMessage
import uz.mahalla.feature.gaming.domain.GamingBooking
import uz.mahalla.feature.gaming.domain.GamingBookingDraft
import uz.mahalla.feature.gaming.domain.GamingBookingError
import uz.mahalla.feature.gaming.domain.GamingBookingStatus
import uz.mahalla.feature.gaming.domain.GamingZone
import uz.mahalla.feature.onboarding.ui.OnboardingNotice
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums
import java.time.Instant

/**
 * Игровые зоны заведения (issue #98): список зон и бронь в шторке.
 *
 * До этого экрана категория `GAMING` в каталоге была, а забронировать в ней
 * было нечего: `PlaceAction.Booking` доезжал до эффекта и упирался в `else ->
 * Unit`.
 */
@Composable
fun GamingZonesScreen(
    onMyBookings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GamingZonesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                GamingZonesEffect.OpenMyBookings -> onMyBookings()
            }
        }
    }

    // Зону могли занять, пока приложение было в фоне: предлагать бронь того,
    // чего уже нет, — обещание, которое сервер не выполнит.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(GamingZonesEvent.ScreenResumed)
    }

    GamingZonesContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun GamingZonesContent(
    state: GamingZonesState,
    onEvent: (GamingZonesEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(
            title = state.placeName.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.gaming_title),
            onBack = onBack,
        )
        MahallaPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(GamingZonesEvent.Refreshed) },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.gap),
            ) {
                state.confirmed?.let { booking ->
                    item(key = "confirmed") {
                        ConfirmationNotice(booking = booking, onEvent = onEvent)
                    }
                }
                zoneItems(state = state, onEvent = onEvent)
            }
        }
    }

    val zone = state.selectedZone
    val draft = state.draft
    if (zone != null && draft != null) {
        BookingSheet(state = state, zone = zone, draft = draft, onEvent = onEvent)
    }
}

/**
 * Состояния разложены руками, а не через `ScreenStateHost`: тот рисует
 * `ApiErrorState` с собственной прокруткой, а внутри `LazyColumn` вложенная
 * прокрутка меряется бесконечной высотой и роняет измерение (issue #62).
 */
private fun LazyListScope.zoneItems(
    state: GamingZonesState,
    onEvent: (GamingZonesEvent) -> Unit,
) {
    when (val zones = state.zones) {
        is ScreenState.Loading -> item(key = "loading") {
            ListSkeleton(itemCount = LIST_SKELETONS)
        }

        // Пустой список — ответ сервера, а не поломка: у заведения просто ещё
        // нет зон.
        is ScreenState.Empty -> item(key = "empty") {
            EmptyState(
                title = stringResource(R.string.gaming_zones_empty_title),
                description = stringResource(R.string.gaming_zones_empty_description),
                icon = Icons.Outlined.SportsEsports,
            )
        }

        is ScreenState.Error -> item(key = "error") {
            InlineFailure(
                failure = zones.failure,
                onRetry = { onEvent(GamingZonesEvent.Retry) },
            )
        }

        is ScreenState.Content -> {
            items(zones.data, key = GamingZone::id) { zone ->
                ZoneCard(zone = zone, onEvent = onEvent)
            }
            item(key = "my-bookings") {
                MahallaButton(
                    text = stringResource(R.string.gaming_my_bookings),
                    onClick = { onEvent(GamingZonesEvent.MyBookingsClicked) },
                    variant = MahallaButtonVariant.Secondary,
                )
            }
        }
    }
}

/**
 * Карточка зоны: имя, тип, цена часа, места и доступность.
 *
 * Кликабельна только та, которую есть чем забронировать
 * ([GamingZone.isBookable]): нажатие в отказ хуже строки, которая не
 * нажимается, — поэтому закрытая зона объясняет себя бейджем.
 */
@Composable
private fun ZoneCard(
    zone: GamingZone,
    onEvent: (GamingZonesEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMahallaColors.current
    MahallaCard(
        modifier = modifier,
        onClick = if (zone.isBookable) {
            { onEvent(GamingZonesEvent.ZoneClicked(zone.id)) }
        } else {
            null
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = zone.name.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.gaming_zone_unnamed),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!zone.isBookable) {
                MahallaBadge(
                    text = stringResource(R.string.gaming_zone_unavailable),
                    tone = MahallaTone.Neutral,
                )
            }
        }

        zone.zoneType?.let { type ->
            Text(
                text = type,
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fgMuted,
            )
        }

        zone.description?.let { description ->
            Text(
                text = description,
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fgMuted,
            )
        }

        // Цена показывается только когда она известна: «0 so'm в час» читалось
        // бы как «бесплатно», а это молчание сервера.
        if (zone.pricePerHour > 0) {
            Text(
                text = stringResource(R.string.gaming_zone_price, zone.pricePerHour.money()),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.titleSmall.merge(TabularNums),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        zone.totalSeats?.let { seats ->
            Text(
                text = pluralStringResource(R.plurals.gaming_zone_seats, seats, seats),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgMuted,
            )
        }

        if (zone.isBookable) {
            MahallaButton(
                text = stringResource(R.string.gaming_book),
                onClick = { onEvent(GamingZonesEvent.ZoneClicked(zone.id)) },
                modifier = Modifier.padding(top = Spacing.item),
                variant = MahallaButtonVariant.Secondary,
            )
        }
    }
}

/**
 * Шторка брони: время начала, длительность и итог.
 *
 * Отказ сервера остаётся **здесь**, рядом с выбором: закрыть шторку значило бы
 * потерять и объяснение, и то, что человек выбрал (issue #34).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookingSheet(
    state: GamingZonesState,
    zone: GamingZone,
    draft: GamingBookingDraft,
    onEvent: (GamingZonesEvent) -> Unit,
) {
    MahallaBottomSheet(
        onDismiss = { onEvent(GamingZonesEvent.SheetDismissed) },
        title = zone.name.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.gaming_zone_unnamed),
    ) {
        Text(
            text = stringResource(R.string.gaming_sheet_time),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        SlotRow(state = state, draft = draft, onEvent = onEvent)

        Text(
            text = stringResource(R.string.gaming_sheet_duration),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = pluralStringResource(
                    R.plurals.gaming_duration_hours,
                    draft.durationHours,
                    draft.durationHours,
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            MahallaQuantityStepper(
                quantity = draft.durationHours,
                onQuantityChange = { onEvent(GamingZonesEvent.DurationChanged(it)) },
                minQuantity = GamingBookingDraft.MIN_HOURS,
                maxQuantity = GamingBookingDraft.MAX_HOURS,
                // «−» на минимуме не превращается в «удалить»: удалять здесь
                // нечего, бронь ещё не создана.
                removable = false,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.gaming_total),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMahallaColors.current.fgMuted,
            )
            Text(
                text = state.totalPrice.money(),
                style = MaterialTheme.typography.titleMedium.merge(TabularNums),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        state.errorText()?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        state.bookingFailure?.let { failure -> InlineFailure(failure = failure) }

        MahallaButton(
            text = stringResource(R.string.gaming_book),
            onClick = { onEvent(GamingZonesEvent.BookClicked) },
            state = ButtonState(enabled = state.canBook, loading = state.isBooking),
        )
    }
}

/**
 * Слоты начала. Считаются на клиенте — расписания зоны бэкенд не отдаёт, —
 * поэтому занятое время видно только по отказу сервера.
 */
@Composable
private fun SlotRow(
    state: GamingZonesState,
    draft: GamingBookingDraft,
    onEvent: (GamingZonesEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        items(state.slots, key = { it.toEpochMilli() }) { slot ->
            MahallaFilterChip(
                label = DateTimeFormatters.time(slot),
                selected = slot == draft.startTime,
                onClick = { onEvent(GamingZonesEvent.SlotSelected(slot)) },
                enabled = !state.isBooking,
            )
        }
    }
}

/** Подтверждение брони: что и на когда. Снимается кнопкой, а не само. */
@Composable
private fun ConfirmationNotice(
    booking: GamingBooking,
    onEvent: (GamingZonesEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val time = booking.startTime
    OnboardingNotice(
        text = if (time != null) {
            stringResource(
                R.string.gaming_booked_at,
                booking.zoneName.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.gaming_zone_unnamed),
                DateTimeFormatters.dateTime(time),
            )
        } else {
            stringResource(R.string.gaming_booked)
        },
        modifier = modifier,
        action = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.item)) {
                MahallaButton(
                    text = stringResource(R.string.gaming_my_bookings),
                    onClick = { onEvent(GamingZonesEvent.MyBookingsClicked) },
                    variant = MahallaButtonVariant.Secondary,
                    fillWidth = false,
                )
                MahallaButton(
                    text = stringResource(R.string.action_close),
                    onClick = { onEvent(GamingZonesEvent.ConfirmationDismissed) },
                    variant = MahallaButtonVariant.Ghost,
                    fillWidth = false,
                )
            }
        },
    )
}

/**
 * Отказ внутри списка: текст сервера, подробности и — если есть чем — повтор.
 * `ApiErrorState` здесь не годится: он прокручивается сам (см. [zoneItems]).
 */
@Composable
private fun InlineFailure(
    failure: ApiFailure,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        Text(
            text = failure.userMessage(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        failure.server?.let { MahallaErrorDetails(server = it) }
        if (onRetry != null) {
            MahallaButton(
                text = stringResource(R.string.action_retry),
                onClick = onRetry,
                variant = MahallaButtonVariant.Secondary,
                fillWidth = false,
            )
        }
    }
}

/** Причины показываются только после первой попытки отправки. */
@Composable
private fun GamingZonesState.errorText(): String? {
    if (!validationShown) return null
    return errors.firstNotNullOfOrNull { error ->
        when (error) {
            GamingBookingError.TimeRequired ->
                stringResource(R.string.gaming_error_time_required)

            GamingBookingError.TimeTooSoon ->
                stringResource(R.string.gaming_error_time_too_soon)

            is GamingBookingError.DurationOutOfRange -> pluralStringResource(
                R.plurals.gaming_error_duration,
                error.max,
                error.min,
                error.max,
            )
        }
    }
}

@Composable
private fun Long.money(): String =
    MoneyFormatter.withCurrency(this, stringResource(R.string.currency_uzs))

private const val LIST_SKELETONS = 3

@ThemeLanguagePreviews
@Composable
private fun GamingZonesPreview() {
    PreviewSurface {
        GamingZonesContent(
            state = GamingZonesState(
                placeName = "Cyber Arena",
                zones = ScreenState.Content(
                    listOf(
                        GamingZone(
                            id = "z-1",
                            placeId = "p-1",
                            name = "PlayStation 5",
                            zoneType = "CONSOLE",
                            pricePerHour = 35_000,
                            totalSeats = 4,
                            isAvailable = true,
                        ),
                        GamingZone(
                            id = "z-2",
                            placeId = "p-1",
                            name = "VR",
                            pricePerHour = 60_000,
                            isAvailable = false,
                        ),
                    ),
                ),
                confirmed = GamingBooking(
                    id = "b-1",
                    zoneName = "PlayStation 5",
                    startTime = Instant.parse("2026-09-04T13:00:00Z"),
                    status = GamingBookingStatus.Confirmed,
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
