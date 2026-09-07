package uz.mahalla.feature.freelancer.ui.orders

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.isLoading
import uz.mahalla.feature.freelancer.data.FreelancerRepository
import uz.mahalla.feature.freelancer.domain.FreelancerOrder
import uz.mahalla.feature.freelancer.domain.FreelancerOrderPage
import javax.inject.Inject

/**
 * «Мои заказы у мастеров» (issue #107).
 *
 * Список перечитывается на каждом возврате на экран: статус меняет мастер
 * (`PUT freelancers/orders/{orderId}/status`, его кабинет — эпик #16), и
 * показанное час назад «ждёт ответа» ничего не стоит.
 */
@HiltViewModel
class MyFreelancerOrdersViewModel @Inject constructor(
    private val repository: FreelancerRepository,
) : MviViewModel<MyFreelancerOrdersState, MyFreelancerOrdersEvent, MyFreelancerOrdersEffect>(
    MyFreelancerOrdersState(),
) {

    private var loadMoreJob: Job? = null
    private var loadedPage = 0

    init {
        load()
    }

    override fun onEvent(event: MyFreelancerOrdersEvent) {
        when (event) {
            // Пока идёт загрузка, перезапрашивать нечего: ответ приедет на уже
            // сменившееся состояние.
            MyFreelancerOrdersEvent.ScreenResumed -> {
                if (!currentState.orders.isLoading && !currentState.isRefreshing) {
                    load(showLoading = false)
                }
            }

            MyFreelancerOrdersEvent.Refreshed -> load(showLoading = false, refreshing = true)
            MyFreelancerOrdersEvent.Retry -> load()
            MyFreelancerOrdersEvent.LoadMore -> loadMore()
        }
    }

    private fun load(showLoading: Boolean = true, refreshing: Boolean = false) {
        loadMoreJob?.cancel()
        loadedPage = 0
        updateState {
            copy(
                orders = if (showLoading) ScreenState.Loading else orders,
                isRefreshing = refreshing,
                isLoadingMore = false,
                loadMoreFailure = null,
            )
        }
        viewModelScope.launch {
            applyPage(repository.myOrders(page = 0))
            if (refreshing) updateState { copy(isRefreshing = false) }
        }
    }

    private fun applyPage(result: ApiResult<FreelancerOrderPage>) {
        when (result) {
            is ApiResult.Failure -> updateState {
                copy(orders = ScreenState.Error(result.failure), hasMore = false)
            }

            is ApiResult.Success -> updateState {
                copy(
                    orders = if (result.data.items.isEmpty()) {
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
     * Догрузка страницы. Провал не стирает уже показанные заказы, но и молча
     * дёргать сеть в цикле нельзя: список не вырос, автотриггер по концу больше
     * не сработает — поэтому хвост переходит в состояние «повторить» вместе с
     * причиной отказа (issue #53).
     */
    private fun loadMore() {
        val state = currentState
        if (!state.hasMore || state.isLoadingMore) return
        val loaded = state.orders as? ScreenState.Content ?: return
        if (loadMoreJob?.isActive == true) return

        val nextPage = loadedPage + 1
        updateState { copy(isLoadingMore = true, loadMoreFailure = null) }
        loadMoreJob = viewModelScope.launch {
            when (val result = repository.myOrders(page = nextPage)) {
                is ApiResult.Failure -> updateState {
                    copy(isLoadingMore = false, loadMoreFailure = result.failure)
                }

                is ApiResult.Success -> {
                    loadedPage = nextPage
                    updateState {
                        copy(
                            orders = ScreenState.Content(
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
     * Заказ может приехать на двух соседних страницах, если список изменился
     * между запросами. В `LazyColumn` это дубликат ключа и падение, поэтому
     * дедупликация по id обязательна.
     */
    private fun appended(
        current: List<FreelancerOrder>,
        next: List<FreelancerOrder>,
    ): List<FreelancerOrder> {
        val known = current.mapTo(mutableSetOf(), FreelancerOrder::id)
        return current + next.filter { known.add(it.id) }
    }
}
