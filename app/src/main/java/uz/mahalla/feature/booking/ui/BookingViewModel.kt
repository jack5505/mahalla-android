package uz.mahalla.feature.booking.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uz.mahalla.core.analytics.AnalyticsEvents
import uz.mahalla.core.analytics.AnalyticsTracker
import uz.mahalla.core.analytics.AnalyticsVertical
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.dataOrNull
import uz.mahalla.core.result.map
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.toListScreenState
import uz.mahalla.feature.booking.data.BookingRepository
import uz.mahalla.feature.booking.domain.Appointment
import uz.mahalla.feature.booking.domain.BookingSlots
import uz.mahalla.navigation.BookingRoute
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

/**
 * Запись на время (issue #97): услуга → день → слот → подтверждение.
 *
 * **Слоты не считаются на клиенте**: занятость знает только сервер, и на
 * каждую пару «услуга + день» уходит свой запрос. Здесь же он и отменяется —
 * человек листает дни быстрее, чем отвечает сеть, и ответ на позавчерашний
 * день, приехавший последним, показал бы чужие слоты.
 *
 * Тот же экран **переносит** запись, если маршрут назвал `rescheduleId`
 * (эпик #11): услуга тогда приезжает готовой, выбирают только день и слот, а
 * подтверждение уходит в `reschedule` вместо `book`. Вместе с ней маршрутом
 * едут подпись переносимой записи и её прежние день и время (issue #155):
 * взять их здесь больше негде — в каталоге услуги может уже не быть, а
 * `GET appointments/{id}` приложение не использует.
 */
@HiltViewModel
class BookingViewModel @Inject constructor(
    private val repository: BookingRepository,
    private val analytics: AnalyticsTracker,
    private val clock: Clock,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<BookingState, BookingEvent, BookingEffect>(BookingState()) {

    private val route: BookingRoute = savedStateHandle.toRoute()
    private var slotsJob: Job? = null

    /** Переносимая запись; пусто — обычная запись с нуля. */
    private val rescheduleId: String = route.rescheduleId.trim()

    init {
        val dates = BookingSlots.dates(clock.instant())
        val preselected = route.serviceId.takeIf { it.isNotBlank() }
        updateState {
            copy(
                placeName = route.placeName,
                dates = dates,
                // День выбран сразу: календарь без выбранного дня не отвечает
                // на вопрос, чьи слоты показаны ниже.
                selectedDate = dates.firstOrNull(),
                selectedServiceId = preselected,
                isReschedule = rescheduleId.isNotEmpty(),
                rescheduleLabel = route.rescheduleLabel.trim(),
                rescheduleDate = parseDate(route.rescheduleDate),
                rescheduleTime = parseTime(route.rescheduleTime),
            )
        }
        loadServices()
        // Услуга уже известна — слоты можно спрашивать не дожидаясь каталога:
        // запросу слотов нужен только её id, а не её цена и название.
        if (preselected != null) loadSlots()
    }

    override fun onEvent(event: BookingEvent) {
        when (event) {
            is BookingEvent.ServiceSelected -> selectService(event.serviceId)
            is BookingEvent.DateSelected -> selectDate(event.date)
            is BookingEvent.TimeSelected -> updateState {
                copy(selectedTime = event.time, bookFailure = null)
            }

            BookingEvent.ServicesRetry -> loadServices()
            BookingEvent.SlotsRetry -> loadSlots()
            BookingEvent.BookClicked -> book()
            BookingEvent.MyAppointmentsClicked -> emitEffect(BookingEffect.OpenMyAppointments)
        }
    }

    private fun loadServices() {
        updateState { copy(services = ScreenState.Loading) }
        viewModelScope.launch {
            val result = repository.services(route.placeId)
            updateState { copy(services = result.toListScreenState()) }
            // Единственная услуга выбирается сама: заставлять нажимать на
            // список из одной строки незачем. Уже выбранную при этом не
            // трогаем — при переносе она приехала маршрутом, и подменить её
            // единственной услугой каталога значило бы перенести человека на
            // другую услугу.
            val single = (result as? ApiResult.Success)?.data?.singleOrNull()
            if (single != null && currentState.selectedServiceId == null) selectService(single.id)
        }
    }

    private fun selectService(serviceId: String) {
        if (currentState.selectedServiceId == serviceId) return
        // Слот от прошлой услуги не переносится: у другой услуги другая
        // длительность, и то же время может быть уже недоступно.
        updateState {
            copy(selectedServiceId = serviceId, selectedTime = null, bookFailure = null)
        }
        loadSlots()
    }

    private fun selectDate(date: LocalDate) {
        if (currentState.selectedDate == date) return
        updateState { copy(selectedDate = date, selectedTime = null, bookFailure = null) }
        loadSlots()
    }

    /**
     * Слоты на выбранную пару «услуга + день».
     *
     * Прошлый запрос отменяется: иначе ответ на день, который человек уже
     * пролистал, приехал бы последним и заменил бы актуальные слоты.
     */
    private fun loadSlots() {
        val state = currentState
        val serviceId = state.selectedServiceId
        val date = state.selectedDate
        slotsJob?.cancel()
        if (serviceId == null || date == null) {
            updateState { copy(slots = ScreenState.Loading) }
            return
        }

        updateState { copy(slots = ScreenState.Loading) }
        slotsJob = viewModelScope.launch {
            val result = repository.slots(
                placeId = route.placeId,
                serviceId = serviceId,
                date = date,
            )
            updateState { copy(slots = result.toListScreenState()) }
        }
    }

    /**
     * Подтверждение.
     *
     * Экран после успеха **не уходит** сам: молчаливый переход читается как
     * «ничего не произошло» (issue #49). Показывается подтверждение с
     * временем, и уже с него человек идёт в «мои записи».
     */
    private fun book() {
        val state = currentState
        val serviceId = state.selectedServiceId ?: return
        val date = state.selectedDate ?: return
        val time = state.selectedTime ?: return
        if (!state.canBook) return

        updateState { copy(isBooking = true, bookFailure = null) }
        viewModelScope.launch {
            if (rescheduleId.isEmpty()) {
                val result = repository.book(
                    placeId = route.placeId,
                    serviceId = serviceId,
                    date = date,
                    time = time,
                )
                applyBooked(result, date = date, time = time, previousCancelled = true)
            } else {
                // Перенос — новая запись плюс отмена старой; порядок и его
                // цену объясняет BookingRepository.reschedule.
                val result = repository.reschedule(
                    appointmentId = rescheduleId,
                    placeId = route.placeId,
                    serviceId = serviceId,
                    date = date,
                    time = time,
                )
                applyBooked(
                    result = result.map { it.appointment },
                    date = date,
                    time = time,
                    previousCancelled = result.dataOrNull()?.previousCancelled ?: true,
                )
            }
        }
    }

    /**
     * Общий разбор исхода записи и переноса: с точки зрения экрана они
     * различаются одной строкой подтверждения, а не поведением.
     *
     * Название услуги, день и время подставляются из выбора, если сервер их не
     * назвал: подтверждение без них не отвечает на вопрос, на что и когда
     * записались.
     */
    private fun applyBooked(
        result: ApiResult<Appointment>,
        date: LocalDate,
        time: LocalTime,
        previousCancelled: Boolean,
    ) {
        when (result) {
            is ApiResult.Failure -> updateState {
                copy(isBooking = false, bookFailure = result.failure)
            }

            is ApiResult.Success -> {
                updateState {
                    copy(
                        isBooking = false,
                        previousCancelled = previousCancelled,
                        booked = result.data.copy(
                            serviceName = result.data.serviceName
                                ?: selectedService?.title?.takeIf { it.isNotBlank() }
                                ?: rescheduleLabel.takeIf { it.isNotBlank() },
                            date = result.data.date ?: date,
                            startTime = result.data.startTime ?: time,
                        ),
                    )
                }
                // Перенос отправляет `BOOK` тоже: с точки зрения заведения это
                // новая запись, и она действительно создана — `reschedule`
                // именно так и устроен (`BookingRepository.reschedule`).
                // Из «Моих записей» маршрут приходит с пустым `placeId`
                // (`Appointment.placeId` там nullable) — тогда заведение
                // берётся из ответа сервера, иначе событие отбросил бы
                // репозиторий, и переносы в панель не попадали бы.
                analytics.track(
                    AnalyticsEvents.booked(
                        placeId = route.placeId.ifBlank { result.data.placeId.orEmpty() },
                        vertical = AnalyticsVertical.Booking,
                    ),
                )
            }
        }
    }

    /**
     * Прежние день и время переносимой записи (issue #155). Разбор мягкий:
     * аргументы маршрута переживают смерть процесса и приходят строками, а
     * упасть из-за подписи над календарём экран не вправе — перенос от неё не
     * зависит.
     */
    private fun parseDate(value: String): LocalDate? =
        value.trim().takeIf { it.isNotEmpty() }?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        }

    private fun parseTime(value: String): LocalTime? =
        value.trim().takeIf { it.isNotEmpty() }?.let {
            runCatching { LocalTime.parse(it) }.getOrNull()
        }
}
