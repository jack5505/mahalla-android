package uz.mahalla.feature.booking.ui.appointments

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
import androidx.compose.material.icons.outlined.EventAvailable
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
import uz.mahalla.core.ui.components.MahallaPullToRefresh
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.SectionHeader
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.booking.domain.Appointment
import uz.mahalla.feature.booking.domain.AppointmentSections
import uz.mahalla.feature.booking.domain.AppointmentStatus
import uz.mahalla.feature.booking.ui.InlineFailure
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums
import java.time.LocalDate
import java.time.LocalTime

/**
 * «Мои записи» (issue #97): активные и прошедшие, отмена с подтверждением.
 */
@Composable
fun MyAppointmentsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MyAppointmentsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Заведение могло подтвердить или отменить запись, пока приложение было в
    // фоне; заодно пересчитывается деление на активные и прошедшие — время
    // идёт и без запросов.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(MyAppointmentsEvent.ScreenResumed)
    }

    MyAppointmentsContentScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun MyAppointmentsContentScreen(
    state: MyAppointmentsState,
    onEvent: (MyAppointmentsEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(
            title = stringResource(R.string.my_appointments_title),
            onBack = onBack,
        )
        MahallaPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(MyAppointmentsEvent.Refreshed) },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.gap),
            ) {
                // Отказ отмены — над списком, а не вместо него: записи уже на
                // экране, и прятать их из-за неудавшейся кнопки незачем.
                state.cancelFailure?.let { failure ->
                    item(key = "cancel-failure") { InlineFailure(failure = failure) }
                }
                appointmentItems(state = state, onEvent = onEvent)
            }
        }
    }

    state.confirmCancel?.let { appointment ->
        MahallaDialog(
            title = stringResource(R.string.my_appointments_cancel_title),
            text = stringResource(R.string.my_appointments_cancel_message),
            confirmLabel = stringResource(R.string.my_appointments_cancel),
            onConfirm = { onEvent(MyAppointmentsEvent.CancelConfirmed) },
            onDismiss = { onEvent(MyAppointmentsEvent.CancelDismissed) },
            dismissLabel = stringResource(R.string.my_appointments_cancel_keep),
            destructive = true,
        )
    }
}

/**
 * Состояния разложены руками, а не через `ScreenStateHost`: тот рисует
 * `ApiErrorState` с собственной прокруткой, а внутри `LazyColumn` вложенная
 * прокрутка меряется бесконечной высотой и роняет измерение (issue #62).
 */
private fun LazyListScope.appointmentItems(
    state: MyAppointmentsState,
    onEvent: (MyAppointmentsEvent) -> Unit,
) {
    when (val appointments = state.appointments) {
        is ScreenState.Loading -> item(key = "loading") {
            ListSkeleton(itemCount = LIST_SKELETONS)
        }

        is ScreenState.Empty -> item(key = "empty") {
            EmptyState(
                title = stringResource(R.string.my_appointments_empty_title),
                description = stringResource(R.string.my_appointments_empty_description),
                icon = Icons.Outlined.EventAvailable,
            )
        }

        is ScreenState.Error -> item(key = "error") {
            InlineFailure(
                failure = appointments.failure,
                onRetry = { onEvent(MyAppointmentsEvent.Retry) },
            )
        }

        is ScreenState.Content -> {
            section(
                titleRes = R.string.my_appointments_upcoming,
                appointments = state.sections.upcoming,
                state = state,
                onEvent = onEvent,
            )
            section(
                titleRes = R.string.my_appointments_past,
                appointments = state.sections.past,
                state = state,
                onEvent = onEvent,
            )
            if (state.hasMore || state.loadMoreFailure != null) {
                item(key = "load-more") {
                    LoadMoreItem(
                        state = state,
                        itemCount = appointments.data.size,
                        onEvent = onEvent,
                    )
                }
            }
        }
    }
}

/** Пустой раздел не рисуется вовсе: заголовок без строк ничего не сообщает. */
private fun LazyListScope.section(
    @StringRes titleRes: Int,
    appointments: List<Appointment>,
    state: MyAppointmentsState,
    onEvent: (MyAppointmentsEvent) -> Unit,
) {
    if (appointments.isEmpty()) return
    item(key = "section-$titleRes") { SectionHeader(title = stringResource(titleRes)) }
    items(appointments, key = Appointment::id) { appointment ->
        AppointmentCard(
            appointment = appointment,
            pending = state.pendingCancelId == appointment.id,
            // Пока идёт отмена по одной строке, остальные не трогаем: ответы
            // приехали бы на список, которого уже нет.
            enabled = state.pendingCancelId == null,
            onEvent = onEvent,
        )
    }
}

@Composable
private fun AppointmentCard(
    appointment: Appointment,
    pending: Boolean,
    enabled: Boolean,
    onEvent: (MyAppointmentsEvent) -> Unit,
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
                text = appointment.serviceName
                    ?: stringResource(R.string.my_appointments_unnamed_service),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            MahallaBadge(
                text = stringResource(appointment.status.labelRes()),
                tone = appointment.status.tone(),
            )
        }

        Text(
            text = appointment.whenText(),
            modifier = Modifier.padding(top = Spacing.item),
            style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
            color = MaterialTheme.colorScheme.onSurface,
        )

        appointment.priceSum.takeIf { it > 0 }?.let { price ->
            Text(
                text = MoneyFormatter.withCurrency(price, stringResource(R.string.currency_uzs)),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
                color = colors.fgMuted,
            )
        }

        if (appointment.canCancel) {
            MahallaButton(
                text = stringResource(R.string.my_appointments_cancel),
                onClick = { onEvent(MyAppointmentsEvent.CancelRequested(appointment.id)) },
                modifier = Modifier.padding(top = Spacing.item),
                variant = MahallaButtonVariant.Destructive,
                state = ButtonState(enabled = enabled && !pending, loading = pending),
            )
        }
    }
}

/**
 * Когда. Дата без времени и время без даты — оба случая законны (поля в
 * контракте необязательные), и молчать о записи из-за одного из них нельзя.
 */
@Composable
private fun Appointment.whenText(): String {
    val day = date?.let(DateTimeFormatters::date)
    val time = startTime?.let(DateTimeFormatters::time)
    return when {
        day != null && time != null -> stringResource(R.string.booking_summary_when, day, time)
        day != null -> day
        time != null -> time
        else -> stringResource(R.string.my_appointments_time_unknown)
    }
}

/**
 * Хвост списка: догрузка следующей страницы по достижению конца. Провал
 * показывает кнопку с причиной — автотриггер по `itemCount` больше не
 * сработает, список ведь не вырос.
 */
@Composable
private fun LoadMoreItem(
    state: MyAppointmentsState,
    itemCount: Int,
    onEvent: (MyAppointmentsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val failure = state.loadMoreFailure
    if (failure != null) {
        InlineFailure(
            failure = failure,
            onRetry = { onEvent(MyAppointmentsEvent.LoadMore) },
            modifier = modifier,
        )
        return
    }

    LaunchedEffect(itemCount) { onEvent(MyAppointmentsEvent.LoadMore) }
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
private fun AppointmentStatus.labelRes(): Int = when (this) {
    AppointmentStatus.Pending -> R.string.appointment_status_pending
    AppointmentStatus.Confirmed -> R.string.appointment_status_confirmed
    AppointmentStatus.Cancelled -> R.string.appointment_status_cancelled
    AppointmentStatus.Completed -> R.string.appointment_status_completed
    AppointmentStatus.NoShow -> R.string.appointment_status_no_show
    AppointmentStatus.Unknown -> R.string.appointment_status_unknown
}

/** Отмена — решение человека, а не сбой: красная плашка читалась бы иначе. */
private fun AppointmentStatus.tone(): MahallaTone = when (this) {
    AppointmentStatus.Confirmed -> MahallaTone.Success
    AppointmentStatus.Pending -> MahallaTone.Info
    AppointmentStatus.Completed -> MahallaTone.Neutral
    AppointmentStatus.Cancelled -> MahallaTone.Neutral
    AppointmentStatus.NoShow -> MahallaTone.Error
    AppointmentStatus.Unknown -> MahallaTone.Neutral
}

private const val LIST_SKELETONS = 3
private val LOAD_MORE_INDICATOR = 24.dp

@ThemeLanguagePreviews
@Composable
private fun MyAppointmentsPreview() {
    val upcoming = Appointment(
        id = "a-1",
        serviceName = "Soch olish",
        priceSum = 60_000,
        date = LocalDate.of(2026, 9, 6),
        startTime = LocalTime.of(10, 40),
        status = AppointmentStatus.Confirmed,
    )
    val past = Appointment(
        id = "a-2",
        serviceName = "Soqol",
        date = LocalDate.of(2026, 8, 30),
        startTime = LocalTime.of(18, 0),
        status = AppointmentStatus.Completed,
    )
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        MyAppointmentsContentScreen(
            state = MyAppointmentsState(
                appointments = ScreenState.Content(listOf(upcoming, past)),
                sections = AppointmentSections.Split(
                    upcoming = listOf(upcoming),
                    past = listOf(past),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
