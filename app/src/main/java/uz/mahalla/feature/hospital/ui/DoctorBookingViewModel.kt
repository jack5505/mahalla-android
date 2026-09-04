package uz.mahalla.feature.hospital.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.toListScreenState
import uz.mahalla.feature.hospital.data.HospitalRepository
import uz.mahalla.feature.hospital.domain.DoctorSchedule
import uz.mahalla.navigation.DoctorBookingRoute
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

/**
 * Запись к врачу (issue #99): врач → день → время → жалоба → подтверждение.
 *
 * **Сетка времени считается на клиенте** — и это вынужденно: ручки свободных
 * слотов у больниц нет вовсе (см. [DoctorSchedule]). Поэтому здесь нет ни
 * запроса за слотами, ни его отмены при перелистывании дней, — но есть
 * пересчёт от текущего момента: сегодняшнее «в девять утра» в полдень
 * предлагать нельзя.
 */
@HiltViewModel
class DoctorBookingViewModel @Inject constructor(
    private val repository: HospitalRepository,
    private val clock: Clock,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<DoctorBookingState, DoctorBookingEvent, DoctorBookingEffect>(
    DoctorBookingState(),
) {

    private val route: DoctorBookingRoute = savedStateHandle.toRoute()

    init {
        val dates = DoctorSchedule.dates(clock.instant())
        // День выбран сразу: календарь без выбранного дня не отвечает на
        // вопрос, чьё время показано ниже.
        val today = dates.firstOrNull()
        updateState {
            copy(
                placeName = route.placeName,
                dates = dates,
                draft = draft.copy(date = today),
                times = today?.let { DoctorSchedule.times(it, clock.instant()) }.orEmpty(),
            )
        }
        loadDoctors()
    }

    override fun onEvent(event: DoctorBookingEvent) {
        when (event) {
            is DoctorBookingEvent.DoctorSelected -> selectDoctor(event.doctorId)
            is DoctorBookingEvent.DateSelected -> selectDate(event.date)

            is DoctorBookingEvent.TimeSelected -> updateState {
                copy(draft = draft.copy(time = event.time), bookFailure = null)
            }

            // Отказ снимается на правку: он был про другой текст.
            is DoctorBookingEvent.ComplaintChanged -> updateState {
                copy(draft = draft.copy(complaint = event.text), bookFailure = null)
            }

            DoctorBookingEvent.DoctorsRetry -> loadDoctors()
            DoctorBookingEvent.BookClicked -> book()
            DoctorBookingEvent.MyAppointmentsClicked ->
                emitEffect(DoctorBookingEffect.OpenMyAppointments)
        }
    }

    private fun loadDoctors() {
        updateState { copy(doctors = ScreenState.Loading) }
        viewModelScope.launch {
            val result = repository.doctors(route.placeId)
            updateState { copy(doctors = result.toListScreenState()) }
            // Единственный врач выбирается сам: заставлять нажимать на список
            // из одной строки незачем.
            val single = (result as? ApiResult.Success)?.data?.singleOrNull()
            if (single != null) selectDoctor(single.id)
        }
    }

    private fun selectDoctor(doctorId: String) {
        if (currentState.draft.doctorId == doctorId) return
        // Время при смене врача не сбрасывается: сетка одна на всех (занятость
        // конкретного врача сервер не сообщает), и обнулять выбор незачем.
        updateState { copy(draft = draft.copy(doctorId = doctorId), bookFailure = null) }
    }

    /**
     * Смена дня пересчитывает сетку от текущего момента и **сбрасывает время**:
     * `10:00` от вчерашнего дня записало бы человека не туда, а на сегодня
     * этого времени может уже не быть в списке.
     */
    private fun selectDate(date: LocalDate) {
        if (currentState.draft.date == date) return
        updateState {
            copy(
                draft = draft.copy(date = date, time = null),
                times = DoctorSchedule.times(date, clock.instant()),
                bookFailure = null,
            )
        }
    }

    /**
     * Подтверждение.
     *
     * Экран после успеха **не уходит** сам: молчаливый переход читается как
     * «ничего не произошло» (issue #49). Показывается подтверждение с врачом и
     * временем, и уже с него человек идёт в «мои записи к врачу».
     */
    private fun book() {
        val state = currentState
        val doctor = state.selectedDoctor ?: return
        if (!state.canBook) return
        val draft = state.draft

        updateState { copy(isBooking = true, bookFailure = null) }
        viewModelScope.launch {
            when (val result = repository.book(draft)) {
                is ApiResult.Failure -> updateState {
                    copy(isBooking = false, bookFailure = result.failure)
                }

                is ApiResult.Success -> updateState {
                    copy(
                        isBooking = false,
                        booked = result.data.copy(
                            // Кого именно сервер назовёт в `serviceName`, из
                            // контракта не следует, а подтверждение без имени
                            // не отвечает на вопрос, к кому записались.
                            serviceName = result.data.serviceName
                                ?: doctor.name.takeIf { it.isNotBlank() },
                            date = result.data.date ?: draft.date,
                            startTime = result.data.startTime ?: draft.time,
                        ),
                    )
                }
            }
        }
    }
}
