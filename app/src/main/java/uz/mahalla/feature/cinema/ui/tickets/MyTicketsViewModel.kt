package uz.mahalla.feature.cinema.ui.tickets

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.isLoading
import uz.mahalla.feature.cinema.data.CinemaRepository
import uz.mahalla.feature.cinema.domain.CinemaTicket
import uz.mahalla.feature.cinema.domain.CinemaTicketPage
import uz.mahalla.feature.cinema.domain.CinemaTickets
import javax.inject.Inject

/** У экрана нет переходов наружу: «назад» ведёт туда, откуда его открыли. */
sealed interface MyTicketsEffect : UiEffect

/**
 * «Мои билеты» (issue #106): список, догрузка страниц, возврат с
 * подтверждением.
 *
 * Список перечитывается на каждом возврате на экран: статус меняет кинотеатр
 * (билет предъявлен на входе — `USED`), и показанное час назад ничего не
 * стоит.
 */
@HiltViewModel
class MyTicketsViewModel @Inject constructor(
    private val repository: CinemaRepository,
) : MviViewModel<MyTicketsState, MyTicketsEvent, MyTicketsEffect>(MyTicketsState()) {

    private var loadMoreJob: Job? = null
    private var loadedPage = 0

    init {
        load()
    }

    override fun onEvent(event: MyTicketsEvent) {
        when (event) {
            // Пока идёт загрузка, перезапрашивать нечего: ответ приедет на уже
            // сменившееся состояние.
            MyTicketsEvent.ScreenResumed -> if (!currentState.tickets.isLoading &&
                !currentState.isRefreshing
            ) {
                load(showLoading = false)
            }

            MyTicketsEvent.Refreshed -> load(showLoading = false, refreshing = true)
            MyTicketsEvent.Retry -> load()
            MyTicketsEvent.LoadMore -> loadMore()

            is MyTicketsEvent.CancelRequested -> updateState {
                copy(
                    confirmCancel = ticketOrNull(event.ticketId)?.takeIf { it.canCancel },
                    cancelFailure = null,
                )
            }

            MyTicketsEvent.CancelDismissed -> updateState { copy(confirmCancel = null) }
            MyTicketsEvent.CancelConfirmed -> cancel()
        }
    }

    private fun load(showLoading: Boolean = true, refreshing: Boolean = false) {
        loadMoreJob?.cancel()
        loadedPage = 0
        updateState {
            copy(
                tickets = if (showLoading) ScreenState.Loading else tickets,
                isRefreshing = refreshing,
                isLoadingMore = false,
                loadMoreFailure = null,
                cancelFailure = null,
            )
        }
        viewModelScope.launch {
            applyPage(repository.myTickets(page = 0))
            if (refreshing) updateState { copy(isRefreshing = false) }
        }
    }

    private fun applyPage(result: ApiResult<CinemaTicketPage>) {
        when (result) {
            is ApiResult.Failure -> updateState {
                copy(tickets = ScreenState.Error(result.failure), hasMore = false)
            }

            is ApiResult.Success -> updateState {
                copy(hasMore = result.data.hasMore).withTickets(result.data.items)
            }
        }
    }

    /**
     * Догрузка страницы. Провал не стирает уже показанные билеты, но и молча
     * дёргать сеть в цикле нельзя: список не вырос, автотриггер по концу
     * больше не сработает — поэтому хвост переходит в состояние «повторить»
     * вместе с причиной отказа (issue #53).
     *
     * Номер загруженной страницы считается локально: сервер, не вернувший
     * `page`, отдаёт дефолтный `0`, и «следующей» навсегда осталась бы первая.
     */
    private fun loadMore() {
        val state = currentState
        if (!state.hasMore || state.isLoadingMore) return
        val loaded = state.tickets as? ScreenState.Content ?: return
        if (loadMoreJob?.isActive == true) return

        val nextPage = loadedPage + 1
        updateState { copy(isLoadingMore = true, loadMoreFailure = null) }
        loadMoreJob = viewModelScope.launch {
            when (val result = repository.myTickets(page = nextPage)) {
                is ApiResult.Failure -> updateState {
                    copy(isLoadingMore = false, loadMoreFailure = result.failure)
                }

                is ApiResult.Success -> {
                    loadedPage = nextPage
                    updateState {
                        copy(hasMore = result.data.hasMore, isLoadingMore = false)
                            .withTickets(appended(loaded.data, result.data.items))
                    }
                }
            }
        }
    }

    /**
     * Билет может приехать на двух соседних страницах, если список изменился
     * между запросами. В `LazyColumn` это дубликат ключа и падение, поэтому
     * дедупликация по id обязательна.
     */
    private fun appended(current: List<CinemaTicket>, next: List<CinemaTicket>): List<CinemaTicket> {
        val known = current.mapTo(mutableSetOf(), CinemaTicket::id)
        return current + next.filter { known.add(it.id) }
    }

    /**
     * Возврат. Список после успеха правится на месте, а не перезапрашивается:
     * сервер уже подтвердил результат, а полная перезагрузка сбросила бы
     * догруженный хвост к первой странице.
     *
     * Возвращённый билет из списка не пропадает — он остаётся со своим новым
     * статусом: исчезнуть без следа значило бы оставить человека в сомнении,
     * вернулось ли что-нибудь вообще.
     */
    private fun cancel() {
        val ticket = currentState.confirmCancel ?: return
        if (currentState.pendingCancelId != null) return

        updateState {
            copy(confirmCancel = null, pendingCancelId = ticket.id, cancelFailure = null)
        }
        viewModelScope.launch {
            when (val result = repository.cancel(ticket)) {
                is ApiResult.Failure -> updateState {
                    copy(pendingCancelId = null, cancelFailure = result.failure)
                }

                is ApiResult.Success -> updateState {
                    copy(pendingCancelId = null).withTickets(
                        ticketsOrEmpty().map { item ->
                            if (item.id == ticket.id) result.data else item
                        },
                    )
                }
            }
        }
    }

    /**
     * Список и его порядок всегда меняются вместе: действующие билеты обязаны
     * оставаться сверху и после возврата одного из них.
     */
    private fun MyTicketsState.withTickets(items: List<CinemaTicket>): MyTicketsState = copy(
        tickets = if (items.isEmpty()) {
            ScreenState.Empty
        } else {
            ScreenState.Content(CinemaTickets.ordered(items))
        },
    )

    private fun ticketsOrEmpty(): List<CinemaTicket> =
        (currentState.tickets as? ScreenState.Content)?.data.orEmpty()

    private fun ticketOrNull(id: String): CinemaTicket? =
        ticketsOrEmpty().firstOrNull { it.id == id }
}
