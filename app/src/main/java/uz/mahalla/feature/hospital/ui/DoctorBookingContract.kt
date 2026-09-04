package uz.mahalla.feature.hospital.ui

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.booking.domain.Appointment
import uz.mahalla.feature.hospital.domain.Doctor
import uz.mahalla.feature.hospital.domain.DoctorAppointmentDraft
import java.time.LocalDate
import java.time.LocalTime

/**
 * Состояние экрана записи к врачу (issue #99): врач → день → время → жалоба →
 * подтверждение.
 *
 * Шаги живут на одном прокручиваемом экране, а не в мастере из четырёх окон:
 * выбор врача меняет и цену приёма, и то, к кому человек идёт, — за этим ему
 * пришлось бы ходить назад постоянно (то же решение, что в брони, issue #97).
 *
 * @param times время, которое предлагается выбрать. Это **не** свободные слоты:
 * ручки занятости у больниц нет вовсе, сетку строит
 * [uz.mahalla.feature.hospital.domain.DoctorSchedule], и экран называет её
 * «удобное время». Отдельного состояния загрузки у неё нет — считать нечего.
 * @param draft черновик целиком: выбор врача, дня, времени и жалоба. Правила
 * («что ещё не заполнено», «жалоба слишком длинная») живут в домене — форму
 * нельзя проверить ни скриншотом, ни запросом.
 * @param bookFailure отказ подтверждения вместе с ответом сервера (issue #34).
 * Выбор и набранная жалоба при этом остаются: терять их из-за отказа незачем.
 */
data class DoctorBookingState(
    val placeName: String = "",
    val doctors: ScreenState<List<Doctor>> = ScreenState.Loading,
    val dates: List<LocalDate> = emptyList(),
    val times: List<LocalTime> = emptyList(),
    val draft: DoctorAppointmentDraft = DoctorAppointmentDraft(),
    val isBooking: Boolean = false,
    val bookFailure: ApiFailure? = null,
    val booked: Appointment? = null,
) : UiState {

    val selectedDoctor: Doctor?
        get() = (doctors as? ScreenState.Content)
            ?.data
            ?.firstOrNull { it.id == draft.doctorId }

    /** Подтверждать можно только полностью собранную запись. */
    val canBook: Boolean
        get() = draft.canSubmit && selectedDoctor != null && !isBooking && booked == null
}

sealed interface DoctorBookingEvent : UiEvent {
    data class DoctorSelected(val doctorId: String) : DoctorBookingEvent
    data class DateSelected(val date: LocalDate) : DoctorBookingEvent
    data class TimeSelected(val time: LocalTime) : DoctorBookingEvent
    data class ComplaintChanged(val text: String) : DoctorBookingEvent

    data object DoctorsRetry : DoctorBookingEvent
    data object BookClicked : DoctorBookingEvent

    /** «Мои записи к врачу» — с экрана подтверждения. */
    data object MyAppointmentsClicked : DoctorBookingEvent
}

sealed interface DoctorBookingEffect : UiEffect {
    /**
     * Запись создана. Экран уходит в «мои записи к врачу»: там она приезжает
     * уже с сервера — вместе со статусом, который больница может изменить.
     */
    data object OpenMyAppointments : DoctorBookingEffect
}
