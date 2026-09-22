package uz.mahalla.feature.business.ui.journal

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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import uz.mahalla.core.ui.components.FilterChipUi
import uz.mahalla.core.ui.components.ListSkeleton
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaFilterRow
import uz.mahalla.core.ui.components.MahallaIconButton
import uz.mahalla.core.ui.components.MahallaPullToRefresh
import uz.mahalla.core.ui.components.MahallaSnackbarHost
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.rememberSnackbarController
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.booking.domain.Appointment
import uz.mahalla.feature.booking.domain.AppointmentStatus
import uz.mahalla.feature.booking.domain.AppointmentVertical
import uz.mahalla.feature.business.domain.BusinessAppointmentStatusFlow
import uz.mahalla.feature.business.ui.BusinessInlineFailure
import uz.mahalla.feature.hospital.domain.Doctor
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import java.time.LocalDate

/**
 * Журнал записей на день (issue #289): барбершоп и клиника.
 *
 * Отдельно от живой очереди (`BusinessQueueScreen`): очередь — это талоны
 * без записи на время (`walkin`-ручки), журнал — уже забронированные слоты
 * (`appointments`/`hospitals appointments`), другая сущность бэкенда.
 */
@Composable
fun BusinessJournalScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BusinessJournalViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = rememberSnackbarController()
    val context = LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(BusinessJournalEvent.ScreenResumed)
    }

    LaunchedEffect(viewModel, context) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is BusinessJournalEffect.StatusChanged -> snackbar.show(
                    text = context.getString(
                        R.string.business_journal_status_changed,
                        context.getString(effect.status.journalLabelRes()),
                    ),
                    tone = if (effect.status == AppointmentStatus.Cancelled ||
                        effect.status == AppointmentStatus.NoShow
                    ) {
                        MahallaTone.Warning
                    } else {
                        MahallaTone.Success
                    },
                )
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        BusinessJournalContentScreen(state = state, onEvent = viewModel::onEvent, onBack = onBack)
        MahallaSnackbarHost(
            controller = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun BusinessJournalContentScreen(
    state: BusinessJournalState,
    onEvent: (BusinessJournalEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(
            title = state.placeName.takeIf(String::isNotBlank)
                ?: stringResource(R.string.business_section_journal),
            onBack = onBack,
        )
        DateRow(state = state, onEvent = onEvent)
        val availableStatuses = journalStatuses(state.vertical)
        MahallaFilterRow(
            items = listOf(
                FilterChipUi(id = STATUS_ALL_ID, label = stringResource(R.string.business_orders_filter_all)),
            ) + availableStatuses.map { status ->
                FilterChipUi(id = status.name, label = stringResource(status.journalLabelRes()))
            },
            selectedId = state.statusFilter?.name ?: STATUS_ALL_ID,
            onSelect = { id ->
                val status = availableStatuses.firstOrNull { it.name == id }
                onEvent(BusinessJournalEvent.StatusFilterSelected(status))
            },
        )
        if (state.isDoctorFilterVisible && state.doctors.isNotEmpty()) {
            MahallaFilterRow(
                items = listOf(
                    FilterChipUi(id = DOCTOR_ALL_ID, label = stringResource(R.string.business_journal_doctor_all)),
                ) + state.doctors.map { doctor -> FilterChipUi(id = doctor.id, label = doctor.name) },
                selectedId = state.doctorId ?: DOCTOR_ALL_ID,
                onSelect = { id ->
                    onEvent(
                        BusinessJournalEvent.DoctorFilterSelected(id.takeIf { it != DOCTOR_ALL_ID }),
                    )
                },
            )
        }
        MahallaPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(BusinessJournalEvent.Refreshed) },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.gap),
            ) {
                state.actionFailure?.let { failure ->
                    item(key = "action-failure") { BusinessInlineFailure(failure = failure) }
                }
                journalItems(state = state, onEvent = onEvent)
            }
        }
    }
}

@Composable
private fun DateRow(state: BusinessJournalState, onEvent: (BusinessJournalEvent) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.item),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MahallaIconButton(
            icon = Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = stringResource(R.string.business_journal_prev_day),
            onClick = { onEvent(BusinessJournalEvent.DateShifted(-1L)) },
        )
        Text(
            text = DateTimeFormatters.date(state.date),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        MahallaIconButton(
            icon = Icons.AutoMirrored.Outlined.ArrowForward,
            contentDescription = stringResource(R.string.business_journal_next_day),
            onClick = { onEvent(BusinessJournalEvent.DateShifted(1L)) },
        )
    }
}

private fun LazyListScope.journalItems(
    state: BusinessJournalState,
    onEvent: (BusinessJournalEvent) -> Unit,
) {
    when (val appointments = state.appointments) {
        is ScreenState.Loading -> item(key = "loading") { ListSkeleton(itemCount = 3) }

        is ScreenState.Empty -> item(key = "empty") {
            EmptyState(
                title = stringResource(R.string.business_journal_empty_title),
                description = stringResource(R.string.business_journal_empty_description),
                icon = Icons.Outlined.EventNote,
            )
        }

        is ScreenState.Error -> item(key = "error") {
            BusinessInlineFailure(
                failure = appointments.failure,
                onRetry = { onEvent(BusinessJournalEvent.Retry) },
            )
        }

        is ScreenState.Content -> {
            items(appointments.data, key = Appointment::id) { appointment ->
                JournalCard(
                    appointment = appointment,
                    vertical = state.vertical,
                    pending = state.pendingAppointmentId == appointment.id,
                    enabled = !state.isBusy,
                    onEvent = onEvent,
                )
            }
            if (state.hasMore || state.loadMoreFailure != null) {
                item(key = "load-more") {
                    LoadMoreItem(state = state, itemCount = appointments.data.size, onEvent = onEvent)
                }
            }
        }
    }
}

/** Карточка записи: услуга/врач, время, статус и кнопки следующего шага. */
@Composable
private fun JournalCard(
    appointment: Appointment,
    vertical: AppointmentVertical,
    pending: Boolean,
    enabled: Boolean,
    onEvent: (BusinessJournalEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMahallaColors.current
    val currency = stringResource(R.string.currency_uzs)
    MahallaCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = appointment.serviceName?.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.business_journal_no_service),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            MahallaBadge(
                text = stringResource(appointment.status.journalLabelRes()),
                tone = appointment.status.tone(),
            )
        }

        appointment.startTime?.let { start ->
            val time = appointment.endTime?.let { end ->
                "${DateTimeFormatters.time(start)}–${DateTimeFormatters.time(end)}"
            } ?: DateTimeFormatters.time(start)
            Text(
                text = time,
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgMuted,
            )
        }

        if (appointment.priceSum > 0) {
            Text(
                text = stringResource(
                    R.string.business_journal_price,
                    MoneyFormatter.withCurrency(appointment.priceSum, currency),
                ),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgMuted,
            )
        }

        appointment.complaint?.let { complaint ->
            Text(
                text = stringResource(R.string.business_journal_complaint, complaint),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgMuted,
            )
        }

        val next = BusinessAppointmentStatusFlow.nextStatuses(appointment.status, vertical)
        if (next.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.gap),
                horizontalArrangement = Arrangement.spacedBy(Spacing.item),
            ) {
                next.forEach { status ->
                    MahallaButton(
                        text = stringResource(status.journalActionRes()),
                        onClick = { onEvent(BusinessJournalEvent.StatusSelected(appointment.id, status)) },
                        modifier = Modifier.weight(1f),
                        variant = if (status == AppointmentStatus.Cancelled ||
                            status == AppointmentStatus.NoShow
                        ) {
                            MahallaButtonVariant.Ghost
                        } else {
                            MahallaButtonVariant.Primary
                        },
                        state = ButtonState(enabled = enabled, loading = pending),
                    )
                }
            }
        }
    }
}

/**
 * Хвост списка: догрузка по достижению конца — тот же приём, что у ленты
 * заказов.
 */
@Composable
private fun LoadMoreItem(
    state: BusinessJournalState,
    itemCount: Int,
    onEvent: (BusinessJournalEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val failure = state.loadMoreFailure
    if (failure != null) {
        BusinessInlineFailure(
            failure = failure,
            onRetry = { onEvent(BusinessJournalEvent.LoadMore) },
            modifier = modifier,
        )
        return
    }

    LaunchedEffect(itemCount) { onEvent(BusinessJournalEvent.LoadMore) }
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
 * Статусы, которые можно выбрать вкладкой (issue #289). `Unknown` — не
 * значение бэкенда, фильтровать по нему нечего. `NoShow` — только у
 * барбершопа: `HospitalAppointmentResponse` такого статуса не знает вовсе, и
 * отправить `status=NO_SHOW` в `hospitals/places/{id}/appointments` значило
 * бы попросить фильтр, которого сервер не понимает (та же причина, что у
 * [BusinessAppointmentStatusFlow.nextStatuses]).
 */
private fun journalStatuses(vertical: AppointmentVertical): List<AppointmentStatus> =
    AppointmentStatus.entries.filter {
        it != AppointmentStatus.Unknown && (it != AppointmentStatus.NoShow || vertical == AppointmentVertical.Barber)
    }

/**
 * Подписи журнала — отдельно от [uz.mahalla.feature.booking.ui.appointments.AppointmentStatus.labelRes]
 * (клиентский экран «мои записи»): там `NO_SHOW` — «Вы не пришли», от первого
 * лица клиента, а в журнале заведения смотрит владелец, и то же слово должно
 * звучать от третьего лица («Не пришёл»).
 */
@StringRes
internal fun AppointmentStatus.journalLabelRes(): Int = when (this) {
    AppointmentStatus.Pending -> R.string.business_journal_status_pending
    AppointmentStatus.Confirmed -> R.string.business_journal_status_confirmed
    AppointmentStatus.Completed -> R.string.business_journal_status_completed
    AppointmentStatus.Cancelled -> R.string.business_journal_status_cancelled
    AppointmentStatus.NoShow -> R.string.business_journal_status_no_show
    AppointmentStatus.Unknown -> R.string.business_journal_status_unknown
}

@StringRes
private fun AppointmentStatus.journalActionRes(): Int = when (this) {
    AppointmentStatus.Confirmed -> R.string.business_journal_action_confirm
    AppointmentStatus.Completed -> R.string.business_journal_action_complete
    AppointmentStatus.NoShow -> R.string.business_journal_action_no_show
    AppointmentStatus.Cancelled -> R.string.business_journal_action_cancel

    // В кнопки эти статусы не попадают — `nextStatuses` их не предлагает.
    AppointmentStatus.Pending, AppointmentStatus.Unknown -> R.string.business_journal_status_unknown
}

private fun AppointmentStatus.tone(): MahallaTone = when (this) {
    AppointmentStatus.Pending -> MahallaTone.Warning
    AppointmentStatus.Confirmed -> MahallaTone.Neutral
    AppointmentStatus.Completed -> MahallaTone.Success
    AppointmentStatus.Cancelled, AppointmentStatus.NoShow -> MahallaTone.Error
    AppointmentStatus.Unknown -> MahallaTone.Neutral
}

private val LOAD_MORE_INDICATOR = 24.dp
private const val STATUS_ALL_ID = "ALL"
private const val DOCTOR_ALL_ID = "ALL"

@ThemeLanguagePreviews
@Composable
private fun BusinessJournalScreenPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        BusinessJournalContentScreen(
            state = BusinessJournalState(
                placeName = "Sartaroshxona Alex",
                vertical = AppointmentVertical.Barber,
                date = LocalDate.of(2026, 9, 22),
                doctors = listOf(Doctor(id = "d-1", name = "Dr. Karimova", specialty = "Terapevt")),
                appointments = ScreenState.Content(
                    listOf(
                        Appointment(
                            id = "a-1",
                            serviceName = "Soch olish",
                            priceSum = 45_000,
                            status = AppointmentStatus.Pending,
                        ),
                        Appointment(
                            id = "a-2",
                            serviceName = "Soqol olish",
                            priceSum = 25_000,
                            status = AppointmentStatus.Confirmed,
                        ),
                    ),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
