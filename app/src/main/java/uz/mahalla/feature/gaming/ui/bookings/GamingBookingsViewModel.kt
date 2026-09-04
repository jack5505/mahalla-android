package uz.mahalla.feature.gaming.ui.bookings

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.isLoading
import uz.mahalla.feature.gaming.data.GamingRepository
import uz.mahalla.feature.gaming.domain.GamingBooking
import uz.mahalla.feature.gaming.domain.GamingBookingPage
import javax.inject.Inject

/**
 * «Мои брони» игровых зон (issue #98).
 *
 * Кэша нет: состояние брони меняет заведение (`bookings/{id}/complete`), и
 * список из Room показывал бы вчерашнее «предстоит». Поэтому же список
 * перечитывается на каждом возврате на экран.
 */
@HiltViewModel
class GamingBookingsViewModel @Inject constructor(
    private val repository: GamingRepository,
) : MviViewModel<GamingBookingsState, GamingBookingsEvent, UiEffect>(GamingBookingsState()) {

    private var loadMoreJob: Job? = null
    private var loadedPage = 0

    init {
        load()
    }

    override fun onEvent(event: GamingBookingsEvent) {
        when (event) {
            GamingBookingsEvent.ScreenResumed ->
                if (!currentState.bookings.isLoading && !currentState.isRefreshing) {
                    load(showLoading = false)
                }

            GamingBookingsEvent.Refreshed -> load(showLoading = false, refreshing = true)
            GamingBookingsEvent.Retry -> load()
            GamingBookingsEvent.LoadMore -> loadMore()
        }
    }

    private fun load(showLoading: Boolean = true, refreshing: Boolean = false) {
        loadMoreJob?.cancel()
        loadedPage = 0
        updateState {
            copy(
                bookings = if (showLoading) ScreenState.Loading else bookings,
                isRefreshing = refreshing,
                isLoadingMore = false,
                loadMoreFailure = null,
            )
        }
        viewModelScope.launch {
            applyPage(repository.myBookings(page = 0))
            if (refreshing) updateState { copy(isRefreshing = false) }
        }
    }

    private fun applyPage(result: ApiResult<GamingBookingPage>) {
        when (result) {
            is ApiResult.Failure -> updateState {
                copy(bookings = ScreenState.Error(result.failure), hasMore = false)
            }

            is ApiResult.Success -> updateState {
                copy(
                    bookings = if (result.data.items.isEmpty()) {
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
     * Догрузка страницы. Провал не стирает уже показанные брони, но и молча
     * дёргать сеть в цикле нельзя: список не вырос, автотриггер по концу
     * списка больше не сработает — поэтому хвост переходит в «повторить»
     * вместе с причиной отказа.
     *
     * Номер страницы считается локально: сервер, не вернувший `page`, отдаёт
     * дефолтный `0`, и «следующей» навсегда осталась бы первая (issue #53).
     */
    private fun loadMore() {
        val state = currentState
        if (!state.hasMore || state.isLoadingMore) return
        val loaded = state.bookings as? ScreenState.Content ?: return
        if (loadMoreJob?.isActive == true) return

        val nextPage = loadedPage + 1
        updateState { copy(isLoadingMore = true, loadMoreFailure = null) }
        loadMoreJob = viewModelScope.launch {
            when (val result = repository.myBookings(page = nextPage)) {
                is ApiResult.Failure -> updateState {
                    copy(isLoadingMore = false, loadMoreFailure = result.failure)
                }

                is ApiResult.Success -> {
                    loadedPage = nextPage
                    updateState {
                        copy(
                            bookings = ScreenState.Content(
                                appended(loaded.data, result.data.items),
                            ),
                            hasMore = result.data.hasMore,
                            isLoadingMore = false,
                        )
                    }
                }
            }
        }
    }

    /**
     * Бронь может приехать на двух соседних страницах, если список изменился
     * между запросами. В `LazyColumn` это дубликат ключа и падение, поэтому
     * дедупликация по id обязательна.
     */
    private fun appended(
        current: List<GamingBooking>,
        next: List<GamingBooking>,
    ): List<GamingBooking> {
        val known = current.mapTo(mutableSetOf(), GamingBooking::id)
        return current + next.filter { known.add(it.id) }
    }
}
