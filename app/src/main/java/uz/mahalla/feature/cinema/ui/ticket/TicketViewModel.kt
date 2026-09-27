package uz.mahalla.feature.cinema.ui.ticket

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.cinema.data.CinemaRepository
import uz.mahalla.navigation.TicketRoute
import javax.inject.Inject

/**
 * Карточка билета (issue #183): читается по id при открытии и на
 * pull-to-refresh — статус меняет кинотеатр (билет предъявлен на входе), и
 * снимок из списка или из «моих активностей» устаревает так же быстро, как
 * список «мои билеты».
 */
@HiltViewModel
class TicketViewModel @Inject constructor(
    private val repository: CinemaRepository,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<TicketState, TicketEvent, TicketEffect>(TicketState()) {

    private val ticketId: String = savedStateHandle.toRoute<TicketRoute>().ticketId

    private var loadJob: Job? = null

    init {
        load()
    }

    override fun onEvent(event: TicketEvent) {
        when (event) {
            // Защита от дубля (первый resume, два resume подряд) — общая, см.
            // MviViewModel.onScreenResumed (issue #145, #209).
            TicketEvent.ScreenResumed -> onScreenResumed(
                isLoadInFlight = { loadJob?.isActive == true },
                load = { load(showLoading = false) },
            )

            TicketEvent.Refreshed -> load(showLoading = false, refreshing = true)
            TicketEvent.Retry -> load()
        }
    }

    private fun load(showLoading: Boolean = true, refreshing: Boolean = false) {
        loadJob?.cancel()
        updateState {
            copy(ticket = if (showLoading) ScreenState.Loading else ticket, isRefreshing = refreshing)
        }
        loadJob = viewModelScope.launch {
            val state = when (val result = repository.ticket(ticketId)) {
                is ApiResult.Failure -> ScreenState.Error(result.failure)
                is ApiResult.Success -> ScreenState.Content(result.data)
            }
            updateState { copy(ticket = state, isRefreshing = false) }
        }
    }
}
