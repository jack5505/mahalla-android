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
 * Он же — экран переноса записи ([isReschedule], эпик #11): выбирают там то же
 * самое, кроме услуги.
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
 *
 * @param isReschedule экран переносит уже существующую запись. Услуга тогда не
 * выбирается — она у переносимой записи своя, — и меняется только время.
 * @param rescheduleLabel подпись переносимой записи из «моих записей»
 * (issue #155). Нужна как запасное имя услуги: название из каталога может и не
 * приехать — заведение вправе убрать услугу из списка, — а подтверждать
 * перенос вслепую человек не должен.
 * @param rescheduleDate и [rescheduleTime] — прежние день и время: без них
 * экран не отвечает на вопрос, **с какого** времени переносят. Оба
 * необязательны по контракту (`AppointmentResponse`), и показывается то, что
 * есть.
 * @param previousCancelled удалось ли снять прежнюю запись. Значимо только
 * после успешного переноса; `false` — у человека осталось две записи, и
 * подтверждение обязано сказать об этом прямо.
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
    val isReschedule: Boolean = false,
    val rescheduleLabel: String = "",
    val rescheduleDate: LocalDate? = null,
    val rescheduleTime: LocalTime? = null,
    val previousCancelled: Boolean = true,
) : UiState {

    val selectedService: BarberService?
        get() = (services as? ScreenState.Content)
            ?.data
            ?.firstOrNull { it.id == selectedServiceId }

    /**
     * Подтверждать можно только полностью собранную запись.
     *
     * Проверяется **id** услуги, а не найденная по нему [selectedService]: при
     * переносе id приезжает маршрутом, и заведение вполне могло убрать услугу
     * из списка — но время-то за человеком уже занято, и запретить ему перенос
     * из-за пропавшей строки в каталоге значило бы оставить его с записью,
     * которую можно только отменить.
     */
    val canBook: Boolean
        get() = selectedServiceId != null &&
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
