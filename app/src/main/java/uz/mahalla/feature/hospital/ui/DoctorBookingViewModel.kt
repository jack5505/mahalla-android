package uz.mahalla.feature.hospital.ui

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
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.toListScreenState
import uz.mahalla.feature.hospital.data.HospitalRepository
import uz.mahalla.feature.hospital.domain.DoctorSlots
import uz.mahalla.navigation.DoctorBookingRoute
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

/**
 * Запись к врачу (issue #99): врач → день → время → жалоба → подтверждение.
 *
 * **Слоты не считаются на клиенте** (issue #181): занятость врача знает
 * только сервер, и на каждую пару «врач + день» уходит свой запрос. Здесь же
 * он и отменяется — человек листает дни быстрее, чем отвечает сеть, и ответ
 * на позавчерашний день, приехавший последним, показал бы чужие слоты (то же
 * решение, что в брони, issue #97).
 */
@HiltViewModel
class DoctorBookingViewModel @Inject constructor(
    private val repository: HospitalRepository,
    private val analytics: AnalyticsTracker,
    private val clock: Clock,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<DoctorBookingState, DoctorBookingEvent, DoctorBookingEffect>(
    DoctorBookingState(),
) {

    private val route: DoctorBookingRoute = savedStateHandle.toRoute()
    private var slotsJob: Job? = null

    init {
        val dates = DoctorSlots.dates(clock.instant())
        // День выбран сразу: календарь без выбранного дня не отвечает на
        // вопрос, чьё время показано ниже.
        val today = dates.firstOrNull()
        updateState {
            copy(placeName = route.placeName, dates = dates, draft = draft.copy(date = today))
        }
        loadDoctors()
    }

    override fun onEvent(event: DoctorBookingEvent) {
        when (event) {
            is DoctorBookingEvent.DoctorSelected -> selectDoctor(event.doctorId)
            is DoctorBookingEvent.DateSelected -> selectDate(event.date)

            is DoctorBookingEvent.SlotSelected -> updateState {
                copy(draft = draft.copy(slot = event.slot), bookFailure = null)
            }

            // Отказ снимается на правку: он был про другой текст.
            is DoctorBookingEvent.ComplaintChanged -> updateState {
                copy(draft = draft.copy(complaint = event.text), bookFailure = null)
            }

            DoctorBookingEvent.DoctorsRetry -> loadDoctors()
            DoctorBookingEvent.SlotsRetry -> loadSlots()
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
        // Слот при смене врача сбрасывается: занятость у каждого своя, и то же
        // время может оказаться уже занятым у нового врача.
        updateState {
            copy(draft = draft.copy(doctorId = doctorId, slot = null), bookFailure = null)
        }
        loadSlots()
    }

    private fun selectDate(date: LocalDate) {
        if (currentState.draft.date == date) return
        updateState { copy(draft = draft.copy(date = date, slot = null), bookFailure = null) }
        loadSlots()
    }

    /**
     * Слоты на выбранную пару «врач + день».
     *
     * Прошлый запрос отменяется: иначе ответ на день, который человек уже
     * пролистал, приехал бы последним и заменил бы актуальные слоты.
     */
    private fun loadSlots() {
        val state = currentState
        val doctorId = state.draft.doctorId
        val date = state.draft.date
        slotsJob?.cancel()
        if (doctorId == null || date == null) {
            updateState { copy(slots = ScreenState.Loading) }
            return
        }

        updateState { copy(slots = ScreenState.Loading) }
        slotsJob = viewModelScope.launch {
            val result = repository.slots(doctorId = doctorId, date = date)
            updateState { copy(slots = result.toListScreenState()) }
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

                is ApiResult.Success -> {
                    updateState {
                        copy(
                            isBooking = false,
                            booked = result.data.copy(
                                // Кого именно сервер назовёт в `serviceName`,
                                // из контракта не следует, а подтверждение без
                                // имени не отвечает на вопрос, к кому записались.
                                serviceName = result.data.serviceName
                                    ?: doctor.name.takeIf { it.isNotBlank() },
                                date = result.data.date ?: draft.date,
                                startTime = result.data.startTime ?: draft.slot?.time,
                            ),
                        )
                    }
                    analytics.track(
                        AnalyticsEvents.booked(route.placeId, AnalyticsVertical.Hospital),
                    )
                }
            }
        }
    }
}
