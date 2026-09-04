package uz.mahalla.feature.booking.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.toListScreenState
import uz.mahalla.feature.booking.data.BookingRepository
import uz.mahalla.feature.booking.domain.BookingSlots
import uz.mahalla.navigation.BookingRoute
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

/**
 * Запись на время (issue #97): услуга → день → слот → подтверждение.
 *
 * **Слоты не считаются на клиенте**: занятость знает только сервер, и на
 * каждую пару «услуга + день» уходит свой запрос. Здесь же он и отменяется —
 * человек листает дни быстрее, чем отвечает сеть, и ответ на позавчерашний
 * день, приехавший последним, показал бы чужие слоты.
 */
@HiltViewModel
class BookingViewModel @Inject constructor(
    private val repository: BookingRepository,
    private val clock: Clock,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<BookingState, BookingEvent, BookingEffect>(BookingState()) {

    private val route: BookingRoute = savedStateHandle.toRoute()
    private var slotsJob: Job? = null

    init {
        val dates = BookingSlots.dates(clock.instant())
        updateState {
            copy(
                placeName = route.placeName,
                dates = dates,
                // День выбран сразу: календарь без выбранного дня не отвечает
                // на вопрос, чьи слоты показаны ниже.
                selectedDate = dates.firstOrNull(),
            )
        }
        loadServices()
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
            // список из одной строки незачем.
            val single = (result as? ApiResult.Success)?.data?.singleOrNull()
            if (single != null) selectService(single.id)
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
        val service = state.selectedService ?: return
        val date = state.selectedDate ?: return
        val time = state.selectedTime ?: return
        if (!state.canBook) return

        updateState { copy(isBooking = true, bookFailure = null) }
        viewModelScope.launch {
            val result = repository.book(
                placeId = route.placeId,
                serviceId = service.id,
                date = date,
                time = time,
            )
            when (result) {
                is ApiResult.Failure -> updateState {
                    copy(isBooking = false, bookFailure = result.failure)
                }

                is ApiResult.Success -> updateState {
                    copy(
                        isBooking = false,
                        booked = result.data.copy(
                            // Название услуги сервер может и не вернуть, а
                            // подтверждение без него не отвечает на вопрос,
                            // на что записались.
                            serviceName = result.data.serviceName
                                ?: service.title.takeIf { it.isNotBlank() },
                            date = result.data.date ?: date,
                            startTime = result.data.startTime ?: time,
                        ),
                    )
                }
            }
        }
    }
}
