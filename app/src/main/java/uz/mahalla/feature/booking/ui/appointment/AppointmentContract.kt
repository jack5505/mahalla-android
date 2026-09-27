package uz.mahalla.feature.booking.ui.appointment

import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.booking.domain.Appointment
import uz.mahalla.feature.booking.domain.AppointmentVertical

/**
 * Карточка одной записи по id (issue #183) — экран один на обе вертикали
 * записи, как и список «Мои записи» ([uz.mahalla.feature.booking.ui.appointments.MyAppointmentsViewModel]):
 * общая модель на экране, разные ручки и схемы на бэкенде за
 * [uz.mahalla.feature.booking.data.AppointmentsSource].
 */
data class AppointmentState(
    val vertical: AppointmentVertical = AppointmentVertical.Barber,
    val appointment: ScreenState<Appointment> = ScreenState.Loading,
    val isRefreshing: Boolean = false,
) : UiState

sealed interface AppointmentEvent : UiEvent {
    /** Статус меняет заведение (или врач), и открывают карточку как раз затем, чтобы это увидеть. */
    data object ScreenResumed : AppointmentEvent

    data object Refreshed : AppointmentEvent
    data object Retry : AppointmentEvent
}

/** У экрана нет переходов наружу: «назад» ведёт туда, откуда его открыли. */
sealed interface AppointmentEffect : UiEffect
