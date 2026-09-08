package uz.mahalla.feature.gaming.ui.bookings

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
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.format.DateTimeFormatters
import uz.mahalla.core.format.MoneyFormatter
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.components.EmptyState
import uz.mahalla.core.ui.components.ListSkeleton
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaErrorDetails
import uz.mahalla.core.ui.components.MahallaPullToRefresh
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.userMessage
import uz.mahalla.feature.gaming.domain.GamingBooking
import uz.mahalla.feature.gaming.domain.GamingBookingStatus
import uz.mahalla.feature.onboarding.ui.OnboardingNotice
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums
import java.time.Instant

/**
 * «Мои брони» игровых зон (issue #98).
 *
 * Кнопки отмены здесь нет: ручки отмены брони у бэкенда нет вовсе (см.
 * `GamingApi`). Вместо неё — строка о том, где бронь снимают, потому что
 * молчание экрана читалось бы как «отменить нельзя нигде».
 */
@Composable
fun GamingBookingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GamingBookingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Состояние брони меняет заведение, а не приложение: показанный час назад
    // список ничего не стоит.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(GamingBookingsEvent.ScreenResumed)
    }

    GamingBookingsContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun GamingBookingsContent(
    state: GamingBookingsState,
    onEvent: (GamingBookingsEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(
            title = stringResource(R.string.gaming_my_bookings),
            onBack = onBack,
        )
        MahallaPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(GamingBookingsEvent.Refreshed) },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.gap),
            ) {
                bookingItems(state = state, onEvent = onEvent)
            }
        }
    }
}

/**
 * Состояния разложены руками, а не через `ScreenStateHost`: тот рисует
 * `ApiErrorState` с собственной прокруткой, а внутри `LazyColumn` вложенная
 * прокрутка меряется бесконечной высотой (issue #62).
 */
private fun LazyListScope.bookingItems(
    state: GamingBookingsState,
    onEvent: (GamingBookingsEvent) -> Unit,
) {
    when (val bookings = state.bookings) {
        is ScreenState.Loading -> item(key = "loading") {
            ListSkeleton(itemCount = LIST_SKELETONS)
        }

        is ScreenState.Empty -> item(key = "empty") {
            EmptyState(
                title = stringResource(R.string.gaming_bookings_empty_title),
                description = stringResource(R.string.gaming_bookings_empty_description),
                icon = Icons.Outlined.SportsEsports,
            )
        }

        is ScreenState.Error -> item(key = "error") {
            InlineFailure(
                failure = bookings.failure,
                onRetry = { onEvent(GamingBookingsEvent.Retry) },
            )
        }

        is ScreenState.Content -> {
            items(bookings.data, key = GamingBooking::id) { booking ->
                BookingCard(booking = booking)
            }
            // Отменить бронь в приложении нечем: такой ручки у бэкенда нет.
            // Сказать об этом словами честнее, чем оставить экран без выхода.
            item(key = "cancel-note") {
                OnboardingNotice(text = stringResource(R.string.gaming_cancel_note))
            }
            if (state.hasMore || state.loadMoreFailure != null) {
                item(key = "load-more") {
                    LoadMoreItem(
                        state = state,
                        itemCount = bookings.data.size,
                        onEvent = onEvent,
                    )
                }
            }
        }
    }
}

/**
 * Карточка брони: зона, время, длительность, сумма и состояние.
 *
 * Имя зоны в ответе не приходит — ни в брони, ни в списке. У только что
 * созданной оно есть (его знает экран зон), у остальных подставляется подпись:
 * делать N запросов за зонами каждого заведения ради строки нельзя.
 */
@Composable
private fun BookingCard(booking: GamingBooking, modifier: Modifier = Modifier) {
    val colors = LocalMahallaColors.current
    MahallaCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = booking.zoneName.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.gaming_booking_zone_unknown),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            MahallaBadge(
                text = stringResource(booking.status.labelRes()),
                tone = booking.status.tone(),
            )
        }

        booking.startTime?.let { start ->
            Text(
                text = booking.endTime?.let { end ->
                    stringResource(
                        R.string.gaming_booking_interval,
                        DateTimeFormatters.dateTime(start),
                        DateTimeFormatters.time(end),
                    )
                } ?: DateTimeFormatters.dateTime(start),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        booking.durationHours?.let { hours ->
            Text(
                text = pluralStringResource(R.plurals.gaming_duration_hours, hours, hours),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgMuted,
            )
        }

        booking.totalPrice?.let { price ->
            Text(
                text = MoneyFormatter.withCurrency(
                    price,
                    stringResource(R.string.currency_uzs),
                ),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.titleSmall.merge(TabularNums),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * Отказ внутри списка: текст сервера, подробности и — если есть чем — повтор.
 * `ApiErrorState` здесь не годится: он прокручивается сам (см. [bookingItems]).
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

/**
 * Хвост списка: догрузка следующей страницы по достижению конца. Провал
 * показывает кнопку с причиной — автотриггер по `itemCount` больше не
 * сработает, список ведь не вырос.
 */
@Composable
private fun LoadMoreItem(
    state: GamingBookingsState,
    itemCount: Int,
    onEvent: (GamingBookingsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val failure = state.loadMoreFailure
    if (failure != null) {
        InlineFailure(
            failure = failure,
            onRetry = { onEvent(GamingBookingsEvent.LoadMore) },
            modifier = modifier,
        )
        return
    }

    LaunchedEffect(itemCount) { onEvent(GamingBookingsEvent.LoadMore) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.gap),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(LOAD_MORE_INDICATOR))
    }
}

/**
 * Подписи состояний. Домен про Android не знает, поэтому сопоставление живёт
 * здесь. У [GamingBookingStatus.Unknown] своя строка, а не «подтверждена»:
 * незнакомое значение бэкенда не значит, что бронь в силе.
 */
@StringRes
private fun GamingBookingStatus.labelRes(): Int = when (this) {
    GamingBookingStatus.Confirmed -> R.string.gaming_status_confirmed
    GamingBookingStatus.Active -> R.string.gaming_status_active
    GamingBookingStatus.Completed -> R.string.gaming_status_completed
    GamingBookingStatus.Cancelled -> R.string.gaming_status_cancelled
    GamingBookingStatus.Unknown -> R.string.gaming_status_unknown
}

/** Отмена — не ошибка, а решение: красная плашка читалась бы как поломка. */
private fun GamingBookingStatus.tone(): MahallaTone = when (this) {
    GamingBookingStatus.Confirmed, GamingBookingStatus.Active -> MahallaTone.Info
    GamingBookingStatus.Completed -> MahallaTone.Success
    GamingBookingStatus.Cancelled, GamingBookingStatus.Unknown -> MahallaTone.Neutral
}

private const val LIST_SKELETONS = 3
private val LOAD_MORE_INDICATOR = 24.dp

@ThemeLanguagePreviews
@Composable
private fun GamingBookingsPreview() {
    PreviewSurface {
        GamingBookingsContent(
            state = GamingBookingsState(
                bookings = ScreenState.Content(
                    listOf(
                        GamingBooking(
                            id = "b-1",
                            zoneName = "PlayStation 5",
                            startTime = Instant.parse("2026-09-05T13:00:00Z"),
                            endTime = Instant.parse("2026-09-05T15:00:00Z"),
                            durationHours = 2,
                            totalPrice = 70_000,
                            status = GamingBookingStatus.Confirmed,
                        ),
                    ),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
