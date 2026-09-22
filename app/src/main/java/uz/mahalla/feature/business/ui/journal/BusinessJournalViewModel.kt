package uz.mahalla.feature.business.ui.journal

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.isLoading
import uz.mahalla.feature.booking.domain.Appointment
import uz.mahalla.feature.booking.domain.AppointmentPage
import uz.mahalla.feature.booking.domain.AppointmentStatus
import uz.mahalla.feature.booking.domain.AppointmentVertical
import uz.mahalla.feature.booking.domain.BookingSlots
import uz.mahalla.feature.business.data.BusinessRepository
import uz.mahalla.feature.business.domain.BusinessAppointmentStatusFlow
import uz.mahalla.navigation.BusinessArgs
import java.time.Clock
import javax.inject.Inject

/**
 * Журнал записей на день (issue #289): барбершоп и клиника — принять,
 * отклонить, завершить запись; фильтры по дню, статусу и (у клиники) по врачу.
 *
 * Список перечитывается на каждом возврате: запись создаётся без участия
 * приложения, и показанный десять минут назад журнал пуст ровно тогда, когда
 * он нужнее всего — та же причина, что у [uz.mahalla.feature.business.ui.orders.BusinessOrdersViewModel].
 */
@HiltViewModel
class BusinessJournalViewModel @Inject constructor(
    private val repository: BusinessRepository,
    private val clock: Clock,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<BusinessJournalState, BusinessJournalEvent, BusinessJournalEffect>(
    BusinessJournalState(
        placeName = savedStateHandle.get<String>(BusinessArgs.PLACE_NAME).orEmpty(),
        vertical = AppointmentVertical.byName(savedStateHandle.get<String>(BusinessArgs.VERTICAL)),
        date = BookingSlots.today(clock.instant()),
    ),
) {

    private val placeId: String = savedStateHandle.get<String>(BusinessArgs.PLACE_ID).orEmpty()

    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null
    private var loadedPage = 0

    init {
        load()
        if (currentState.isDoctorFilterVisible) loadDoctors()
    }

    override fun onEvent(event: BusinessJournalEvent) {
        when (event) {
            BusinessJournalEvent.ScreenResumed ->
                if (!currentState.appointments.isLoading && !currentState.isRefreshing) {
                    load(showLoading = false)
                }

            BusinessJournalEvent.Refreshed -> load(showLoading = false, refreshing = true)
            BusinessJournalEvent.Retry -> load()
            BusinessJournalEvent.LoadMore -> loadMore()
            is BusinessJournalEvent.DateShifted -> shiftDate(event.days)
            is BusinessJournalEvent.StatusFilterSelected -> selectStatusFilter(event.status)
            is BusinessJournalEvent.DoctorFilterSelected -> selectDoctorFilter(event.doctorId)
            is BusinessJournalEvent.StatusSelected -> updateStatus(event.appointmentId, event.status)
        }
    }

    private fun shiftDate(days: Long) {
        updateState { copy(date = date.plusDays(days)) }
        load()
    }

    private fun selectStatusFilter(status: AppointmentStatus?) {
        if (status == currentState.statusFilter) return
        updateState { copy(statusFilter = status) }
        load()
    }

    private fun selectDoctorFilter(doctorId: String?) {
        if (doctorId == currentState.doctorId) return
        updateState { copy(doctorId = doctorId) }
        load()
    }

    private fun loadDoctors() {
        viewModelScope.launch {
            val result = repository.doctors(placeId)
            if (result is ApiResult.Success) {
                updateState { copy(doctors = result.data) }
            }
        }
    }

    /**
     * Предыдущая загрузка отменяется: «повторить» поверх pull-to-refresh иначе
     * даёт два параллельных запроса, и выигрывает ответивший последним — то
     * есть возможен откат к более старому списку (та же грабля, что у ленты
     * заказов).
     */
    private fun load(showLoading: Boolean = true, refreshing: Boolean = false) {
        loadJob?.cancel()
        loadMoreJob?.cancel()
        loadedPage = 0
        updateState {
            copy(
                appointments = if (showLoading) ScreenState.Loading else appointments,
                isRefreshing = refreshing,
                isLoadingMore = false,
                loadMoreFailure = null,
                actionFailure = null,
            )
        }
        val state = currentState
        loadJob = viewModelScope.launch {
            val result = repository.journal(
                placeId = placeId,
                vertical = state.vertical,
                date = state.date,
                doctorId = state.doctorId,
                status = state.statusFilter,
                page = 0,
            )
            // Пока шёл запрос, фильтр или день могли смениться — ответ на
            // прежний запрос перезаписал бы список чужими записями.
            if (!isSameQuery(state)) return@launch
            applyPage(result)
            if (refreshing) updateState { copy(isRefreshing = false) }
        }
    }

    private fun applyPage(result: ApiResult<AppointmentPage>) {
        when (result) {
            is ApiResult.Failure -> updateState {
                copy(appointments = ScreenState.Error(result.failure), hasMore = false)
            }

            is ApiResult.Success -> updateState {
                copy(
                    appointments = if (result.data.items.isEmpty()) {
                        ScreenState.Empty
                    } else {
                        ScreenState.Content(result.data.items)
                    },
                    hasMore = result.data.hasMore,
                )
            }
        }
    }

    /**
     * Догрузка страницы. Номер считается локально: сервер, не вернувший
     * `page`, отдаёт дефолтный `0`, и «следующей» навсегда осталась бы первая.
     */
    private fun loadMore() {
        val state = currentState
        if (!state.hasMore || state.isLoadingMore) return
        if (state.appointments !is ScreenState.Content) return
        if (loadMoreJob?.isActive == true) return

        val nextPage = loadedPage + 1
        updateState { copy(isLoadingMore = true, loadMoreFailure = null) }
        loadMoreJob = viewModelScope.launch {
            val result = repository.journal(
                placeId = placeId,
                vertical = state.vertical,
                date = state.date,
                doctorId = state.doctorId,
                status = state.statusFilter,
                page = nextPage,
            )
            if (!isSameQuery(state)) return@launch
            when (result) {
                is ApiResult.Failure -> updateState {
                    copy(isLoadingMore = false, loadMoreFailure = result.failure)
                }

                is ApiResult.Success -> {
                    loadedPage = nextPage
                    val current = (currentState.appointments as? ScreenState.Content)?.data.orEmpty()
                    val merged = appended(current, result.data.items)
                    updateState {
                        copy(
                            appointments = ScreenState.Content(merged),
                            hasMore = result.data.hasMore && merged.size > current.size,
                            isLoadingMore = false,
                        )
                    }
                }
            }
        }
    }

    private fun isSameQuery(requested: BusinessJournalState): Boolean {
        val state = currentState
        return state.date == requested.date &&
            state.statusFilter == requested.statusFilter &&
            state.doctorId == requested.doctorId
    }

    /**
     * Запись может приехать на двух соседних страницах, если журнал изменился
     * между запросами. В `LazyColumn` это дубликат ключа и падение.
     */
    private fun appended(current: List<Appointment>, next: List<Appointment>): List<Appointment> {
        val known = current.mapTo(mutableSetOf(), Appointment::id)
        return current + next.filter { known.add(it.id) }
    }

    /**
     * Смена статуса. Переход проверяется **до** запроса: экран рисует только
     * разрешённые кнопки, но событие может прийти на устаревший список.
     *
     * После успеха запись правится на месте, но остаётся в списке даже когда
     * выпадает из фильтра — тот же приём, что у ленты заказов: строка,
     * исчезнувшая ровно в момент нажатия, читается как «нажал не туда».
     */
    private fun updateStatus(appointmentId: String, status: AppointmentStatus) {
        val state = currentState
        if (state.isBusy) return
        val appointment = appointmentOrNull(appointmentId) ?: return
        if (!BusinessAppointmentStatusFlow.isAllowed(appointment.status, status, state.vertical)) return

        updateState { copy(pendingAppointmentId = appointmentId, actionFailure = null) }
        viewModelScope.launch {
            val result = repository.updateAppointmentStatus(
                placeId = placeId,
                appointmentId = appointmentId,
                vertical = state.vertical,
                status = status,
            )
            when (result) {
                is ApiResult.Failure -> updateState {
                    copy(pendingAppointmentId = null, actionFailure = result.failure)
                }

                is ApiResult.Success -> {
                    updateState { copy(appointments = replaced(result.data), pendingAppointmentId = null) }
                    emitEffect(BusinessJournalEffect.StatusChanged(result.data.status))
                }
            }
        }
    }

    private fun replaced(updated: Appointment): ScreenState<List<Appointment>> {
        val content = currentState.appointments as? ScreenState.Content ?: return currentState.appointments
        return ScreenState.Content(content.data.map { if (it.id == updated.id) updated else it })
    }

    private fun appointmentOrNull(appointmentId: String): Appointment? =
        (currentState.appointments as? ScreenState.Content)?.data
            ?.firstOrNull { it.id == appointmentId }
}
