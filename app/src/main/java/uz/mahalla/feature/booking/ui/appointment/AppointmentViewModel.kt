package uz.mahalla.feature.booking.ui.appointment

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.booking.data.AppointmentsSource
import uz.mahalla.feature.booking.data.BookingRepository
import uz.mahalla.feature.booking.domain.AppointmentVertical
import uz.mahalla.feature.hospital.data.HospitalRepository
import uz.mahalla.navigation.AppointmentRoute
import javax.inject.Inject

/**
 * Карточка записи (issue #183): читается по id при открытии и на
 * pull-to-refresh — статус меняет заведение или врач (`PUT
 * appointments/{id}/status`, бизнес-панель эпика #16), и снимок, показанный из
 * «моих активностей», устаревает так же, как строка списка «мои записи».
 *
 * Источник выбирается по [AppointmentRoute.vertical] — тот же приём, что у
 * [uz.mahalla.feature.booking.ui.appointments.MyAppointmentsViewModel].
 */
@HiltViewModel
class AppointmentViewModel @Inject constructor(
    bookingRepository: BookingRepository,
    hospitalRepository: HospitalRepository,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<AppointmentState, AppointmentEvent, AppointmentEffect>(AppointmentState()) {

    private val route: AppointmentRoute = savedStateHandle.toRoute()

    private val vertical = AppointmentVertical.byName(route.vertical)

    private val repository: AppointmentsSource = when (vertical) {
        AppointmentVertical.Barber -> bookingRepository
        AppointmentVertical.Doctor -> hospitalRepository
    }

    private var loadJob: Job? = null

    init {
        updateState { copy(vertical = this@AppointmentViewModel.vertical) }
        load()
    }

    override fun onEvent(event: AppointmentEvent) {
        when (event) {
            // Защита от дубля (первый resume, два resume подряд) — общая, см.
            // MviViewModel.onScreenResumed (issue #145, #209).
            AppointmentEvent.ScreenResumed -> onScreenResumed(
                isLoadInFlight = { loadJob?.isActive == true },
                load = { load(showLoading = false) },
            )

            AppointmentEvent.Refreshed -> load(showLoading = false, refreshing = true)
            AppointmentEvent.Retry -> load()
        }
    }

    private fun load(showLoading: Boolean = true, refreshing: Boolean = false) {
        loadJob?.cancel()
        updateState {
            copy(
                appointment = if (showLoading) ScreenState.Loading else appointment,
                isRefreshing = refreshing,
            )
        }
        loadJob = viewModelScope.launch {
            val state = when (val result = repository.appointment(route.appointmentId)) {
                is ApiResult.Failure -> ScreenState.Error(result.failure)
                is ApiResult.Success -> ScreenState.Content(result.data)
            }
            updateState { copy(appointment = state, isRefreshing = false) }
        }
    }
}
