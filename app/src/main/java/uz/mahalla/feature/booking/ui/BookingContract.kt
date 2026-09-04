package uz.mahalla.feature.booking.ui

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.booking.domain.Appointment
import uz.mahalla.feature.booking.domain.BarberService
import java.time.LocalDate
import java.time.LocalTime

/**
 * Состояние экрана записи (issue #97): услуга → день → слот → подтверждение.
 *
 * Шаги живут на одном прокручиваемом экране, а не в мастере из четырёх окон:
 * выбор услуги меняет и слоты, и цену, и возвращаться назад за этим человеку
 * приходится постоянно.
 *
 * @param slots состояние **отдельно** от [services]: слоты перезапрашиваются
 * на каждую смену услуги и дня, и отказ по ним не должен прятать уже
 * выбранную услугу.
 * @param selectedDate день всегда выбран (по умолчанию сегодня) — календарь
 * без выбранного дня не отвечает на вопрос, чьи слоты показаны ниже.
 * @param selectedTime слот; сбрасывается при смене услуги или дня — оставить
 * `10:00` от вчерашнего дня значило бы записать человека не туда.
 * @param bookFailure отказ подтверждения вместе с ответом сервера (issue #34).
 * Выбор при этом остаётся: терять его из-за отказа незачем.
 */
data class BookingState(
    val placeName: String = "",
    val services: ScreenState<List<BarberService>> = ScreenState.Loading,
    val selectedServiceId: String? = null,
    val dates: List<LocalDate> = emptyList(),
    val selectedDate: LocalDate? = null,
    val slots: ScreenState<List<LocalTime>> = ScreenState.Loading,
    val selectedTime: LocalTime? = null,
    val isBooking: Boolean = false,
    val bookFailure: ApiFailure? = null,
    val booked: Appointment? = null,
) : UiState {

    val selectedService: BarberService?
        get() = (services as? ScreenState.Content)
            ?.data
            ?.firstOrNull { it.id == selectedServiceId }

    /** Подтверждать можно только полностью собранную запись. */
    val canBook: Boolean
        get() = selectedService != null &&
            selectedDate != null &&
            selectedTime != null &&
            !isBooking &&
            booked == null
}

sealed interface BookingEvent : UiEvent {
    data class ServiceSelected(val serviceId: String) : BookingEvent
    data class DateSelected(val date: LocalDate) : BookingEvent
    data class TimeSelected(val time: LocalTime) : BookingEvent

    data object ServicesRetry : BookingEvent
    data object SlotsRetry : BookingEvent
    data object BookClicked : BookingEvent

    /** «Мои записи» — с экрана подтверждения. */
    data object MyAppointmentsClicked : BookingEvent
}

sealed interface BookingEffect : UiEffect {
    /**
     * Запись создана. Экран уходит в «мои записи»: там она приезжает уже с
     * сервера — вместе со статусом, который заведение может изменить.
     */
    data object OpenMyAppointments : BookingEffect
}
