package uz.mahalla.feature.business.ui.journal

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.booking.domain.Appointment
import uz.mahalla.feature.booking.domain.AppointmentStatus
import uz.mahalla.feature.booking.domain.AppointmentVertical
import uz.mahalla.feature.hospital.domain.Doctor
import java.time.LocalDate

/**
 * Состояние журнала записей на день (issue #289) — барбершоп и клиника.
 *
 * @param date день журнала; по умолчанию — сегодня в зоне заведения
 * ([uz.mahalla.core.format.DateTimeFormatters.AppZone]).
 * @param statusFilter вкладка статуса; `null` — записи любого статуса.
 * @param doctorId фильтр по врачу — только у [AppointmentVertical.Doctor].
 * @param doctors список врачей заведения для фильтра; у барбершопа всегда
 * пуст — своих мастеров панель не разводит.
 * @param pendingAppointmentId запись, по которой идёт смена статуса.
 */
data class BusinessJournalState(
    val placeName: String = "",
    val vertical: AppointmentVertical = AppointmentVertical.Barber,
    val date: LocalDate = LocalDate.now(),
    val statusFilter: AppointmentStatus? = null,
    val doctorId: String? = null,
    val doctors: List<Doctor> = emptyList(),
    val appointments: ScreenState<List<Appointment>> = ScreenState.Loading,
    val isRefreshing: Boolean = false,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val pendingAppointmentId: String? = null,
    val actionFailure: ApiFailure? = null,
    val loadMoreFailure: ApiFailure? = null,
) : UiState {

    /** Фильтр по врачу есть только у клиники — у барбершопа мастеров не разводят. */
    val isDoctorFilterVisible: Boolean get() = vertical == AppointmentVertical.Doctor

    val isBusy: Boolean get() = pendingAppointmentId != null
}

sealed interface BusinessJournalEvent : UiEvent {
    /** Записи приходят, пока экран в фоне, — ради них сюда и возвращаются. */
    data object ScreenResumed : BusinessJournalEvent

    data object Refreshed : BusinessJournalEvent
    data object Retry : BusinessJournalEvent
    data object LoadMore : BusinessJournalEvent

    /** Сдвиг дня: `-1` — вчера, `+1` — завтра. */
    data class DateShifted(val days: Long) : BusinessJournalEvent

    data class StatusFilterSelected(val status: AppointmentStatus?) : BusinessJournalEvent
    data class DoctorFilterSelected(val doctorId: String?) : BusinessJournalEvent

    data class StatusSelected(val appointmentId: String, val status: AppointmentStatus) :
        BusinessJournalEvent
}

sealed interface BusinessJournalEffect : UiEffect {
    /** Статус сменён. Едет сам статус, а не готовая строка: подписи — в ресурсах. */
    data class StatusChanged(val status: AppointmentStatus) : BusinessJournalEffect
}
