package uz.mahalla.feature.booking.ui.appointments

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.isLoading
import uz.mahalla.feature.booking.data.BookingRepository
import uz.mahalla.feature.booking.domain.Appointment
import uz.mahalla.feature.booking.domain.AppointmentPage
import uz.mahalla.feature.booking.domain.AppointmentSections
import java.time.Clock
import javax.inject.Inject

/** У экрана нет переходов наружу: «назад» ведёт туда, откуда его открыли. */
sealed interface MyAppointmentsEffect : UiEffect

/**
 * «Мои записи» (issue #97): активные и прошедшие, отмена с подтверждением.
 *
 * Список перечитывается на каждом возврате на экран: статус меняет заведение
 * (`PUT appointments/{id}/status`, бизнес-панель — эпик #16), и показанное час
 * назад «ждёт подтверждения» ничего не стоит.
 */
@HiltViewModel
class MyAppointmentsViewModel @Inject constructor(
    private val repository: BookingRepository,
    private val clock: Clock,
) : MviViewModel<MyAppointmentsState, MyAppointmentsEvent, MyAppointmentsEffect>(
    MyAppointmentsState(),
) {

    private var loadMoreJob: Job? = null
    private var loadedPage = 0

    init {
        load()
    }

    override fun onEvent(event: MyAppointmentsEvent) {
        when (event) {
            // Пока идёт загрузка, перезапрашивать нечего: ответ приедет на уже
            // сменившееся состояние. Деление на активные и прошедшие при этом
            // пересчитывается всегда — время идёт и без запросов.
            MyAppointmentsEvent.ScreenResumed -> {
                updateState { withSections(appointmentsOrEmpty()) }
                if (!currentState.appointments.isLoading && !currentState.isRefreshing) {
                    load(showLoading = false)
                }
            }

            MyAppointmentsEvent.Refreshed -> load(showLoading = false, refreshing = true)
            MyAppointmentsEvent.Retry -> load()
            MyAppointmentsEvent.LoadMore -> loadMore()

            is MyAppointmentsEvent.CancelRequested -> updateState {
                copy(
                    confirmCancel = appointmentOrNull(event.appointmentId)?.takeIf { it.canCancel },
                    cancelFailure = null,
                )
            }

            MyAppointmentsEvent.CancelDismissed -> updateState { copy(confirmCancel = null) }
            MyAppointmentsEvent.CancelConfirmed -> cancel()
        }
    }

    private fun load(showLoading: Boolean = true, refreshing: Boolean = false) {
        loadMoreJob?.cancel()
        loadedPage = 0
        updateState {
            copy(
                appointments = if (showLoading) ScreenState.Loading else appointments,
                isRefreshing = refreshing,
                isLoadingMore = false,
                loadMoreFailure = null,
                cancelFailure = null,
            )
        }
        viewModelScope.launch {
            applyPage(repository.myAppointments(page = 0))
            if (refreshing) updateState { copy(isRefreshing = false) }
        }
    }

    private fun applyPage(result: ApiResult<AppointmentPage>) {
        when (result) {
            is ApiResult.Failure -> updateState {
                copy(appointments = ScreenState.Error(result.failure), hasMore = false)
            }

            is ApiResult.Success -> updateState {
                copy(hasMore = result.data.hasMore).withSections(result.data.items)
            }
        }
    }

    /**
     * Догрузка страницы. Провал не стирает уже показанные записи, но и молча
     * дёргать сеть в цикле нельзя: список не вырос, автотриггер по концу больше
     * не сработает — поэтому хвост переходит в состояние «повторить» вместе с
     * причиной отказа (issue #53).
     *
     * Номер загруженной страницы считается локально: сервер, не вернувший
     * `page`, отдаёт дефолтный `0`, и «следующей» навсегда осталась бы первая.
     */
    private fun loadMore() {
        val state = currentState
        if (!state.hasMore || state.isLoadingMore) return
        val loaded = state.appointments as? ScreenState.Content ?: return
        if (loadMoreJob?.isActive == true) return

        val nextPage = loadedPage + 1
        updateState { copy(isLoadingMore = true, loadMoreFailure = null) }
        loadMoreJob = viewModelScope.launch {
            when (val result = repository.myAppointments(page = nextPage)) {
                is ApiResult.Failure -> updateState {
                    copy(isLoadingMore = false, loadMoreFailure = result.failure)
                }

                is ApiResult.Success -> {
                    loadedPage = nextPage
                    updateState {
                        copy(hasMore = result.data.hasMore, isLoadingMore = false)
                            .withSections(appended(loaded.data, result.data.items))
                    }
                }
            }
        }
    }

    /**
     * Запись может приехать на двух соседних страницах, если список изменился
     * между запросами. В `LazyColumn` это дубликат ключа и падение, поэтому
     * дедупликация по id обязательна.
     */
    private fun appended(current: List<Appointment>, next: List<Appointment>): List<Appointment> {
        val known = current.mapTo(mutableSetOf(), Appointment::id)
        return current + next.filter { known.add(it.id) }
    }

    /**
     * Отмена. Список после успеха правится на месте, а не перезапрашивается:
     * сервер уже подтвердил результат, а полная перезагрузка сбросила бы
     * догруженный хвост к первой странице.
     *
     * Отменённая запись из списка не пропадает — она переезжает в «прошедшие»
     * с новым статусом: исчезнуть без следа значило бы оставить человека в
     * сомнении, отменилось ли что-нибудь вообще.
     */
    private fun cancel() {
        val appointment = currentState.confirmCancel ?: return
        if (currentState.pendingCancelId != null) return

        updateState {
            copy(confirmCancel = null, pendingCancelId = appointment.id, cancelFailure = null)
        }
        viewModelScope.launch {
            when (val result = repository.cancel(appointment)) {
                is ApiResult.Failure -> updateState {
                    copy(pendingCancelId = null, cancelFailure = result.failure)
                }

                is ApiResult.Success -> updateState {
                    copy(pendingCancelId = null).withSections(
                        appointmentsOrEmpty().map { item ->
                            if (item.id == appointment.id) result.data else item
                        },
                    )
                }
            }
        }
    }

    /**
     * Список и его деление всегда меняются вместе: разъехаться им нельзя —
     * тогда на экране окажется одно, а в состоянии другое.
     */
    private fun MyAppointmentsState.withSections(
        items: List<Appointment>,
    ): MyAppointmentsState = copy(
        appointments = if (items.isEmpty()) ScreenState.Empty else ScreenState.Content(items),
        sections = AppointmentSections.split(items, clock.instant()),
    )

    private fun appointmentsOrEmpty(): List<Appointment> =
        (currentState.appointments as? ScreenState.Content)?.data.orEmpty()

    private fun appointmentOrNull(id: String): Appointment? =
        appointmentsOrEmpty().firstOrNull { it.id == id }
}
