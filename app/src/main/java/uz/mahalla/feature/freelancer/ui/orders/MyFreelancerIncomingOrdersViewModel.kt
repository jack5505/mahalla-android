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
import uz.mahalla.feature.freelancer.domain.FreelancerOrderStatus
import javax.inject.Inject

/**
 * Входящие заказы мастера (issue #190).
 *
 * Список перечитывается на каждом возврате на экран: новый заказ мог прийти,
 * пока приложение было в фоне (то же правило, что у
 * [MyFreelancerOrdersViewModel]). После успешной смены статуса список тоже
 * перечитывается, а не правится на клиенте: ответ `PUT .../status` не
 * разбирается как заказ (см. [FreelancerRepository.updateOrderStatus]), и
 * единственный источник правды после действия — сам сервер.
 */
@HiltViewModel
class MyFreelancerIncomingOrdersViewModel @Inject constructor(
    private val repository: FreelancerRepository,
) : MviViewModel<
    MyFreelancerIncomingOrdersState,
    MyFreelancerIncomingOrdersEvent,
    MyFreelancerIncomingOrdersEffect,
    >(
    MyFreelancerIncomingOrdersState(),
) {

    private var loadMoreJob: Job? = null
    private var loadedPage = 0

    init {
        load()
    }

    override fun onEvent(event: MyFreelancerIncomingOrdersEvent) {
        when (event) {
            MyFreelancerIncomingOrdersEvent.ScreenResumed -> {
                if (!currentState.orders.isLoading && !currentState.isRefreshing) {
                    load(showLoading = false)
                }
            }

            MyFreelancerIncomingOrdersEvent.Refreshed ->
                load(showLoading = false, refreshing = true)

            MyFreelancerIncomingOrdersEvent.Retry -> load()
            MyFreelancerIncomingOrdersEvent.LoadMore -> loadMore()

            is MyFreelancerIncomingOrdersEvent.AcceptClicked ->
                changeStatus(event.orderId, FreelancerOrderStatus.Accepted)

            is MyFreelancerIncomingOrdersEvent.RejectClicked ->
                changeStatus(event.orderId, FreelancerOrderStatus.Rejected)

            is MyFreelancerIncomingOrdersEvent.CompleteClicked ->
                changeStatus(event.orderId, FreelancerOrderStatus.Completed)
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
            applyPage(repository.incomingOrders(page = 0))
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
     * дёргать сеть в цикле нельзя: список не вырос, автотриггер по концу
     * больше не сработает — поэтому хвост переходит в состояние «повторить»
     * вместе с причиной отказа (то же правило, что у [MyFreelancerOrdersViewModel]).
     */
    private fun loadMore() {
        val state = currentState
        if (!state.hasMore || state.isLoadingMore) return
        val loaded = state.orders as? ScreenState.Content ?: return
        if (loadMoreJob?.isActive == true) return

        val nextPage = loadedPage + 1
        updateState { copy(isLoadingMore = true, loadMoreFailure = null) }
        loadMoreJob = viewModelScope.launch {
            when (val result = repository.incomingOrders(page = nextPage)) {
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

    /**
     * Смена статуса — одна за раз ([MyFreelancerIncomingOrdersState.pendingOrderId]):
     * кнопки других заказов при этом остаются кликабельными, а повторный клик
     * по тому же заказу игнорируется, пока летит предыдущий запрос.
     *
     * Предыдущий отказ действия очищается сразу, не дожидаясь ответа: иначе
     * баннер прежней ошибки висел бы поверх уже идущей новой попытки.
     */
    private fun changeStatus(orderId: String, status: FreelancerOrderStatus) {
        if (currentState.pendingOrderId != null) return
        updateState { copy(pendingOrderId = orderId, actionFailure = null) }
        viewModelScope.launch {
            when (val result = repository.updateOrderStatus(orderId, status)) {
                is ApiResult.Failure -> updateState {
                    copy(pendingOrderId = null, actionFailure = result.failure)
                }

                is ApiResult.Success -> {
                    updateState { copy(pendingOrderId = null) }
                    // Источник правды — сервер: ответ смены статуса не
                    // разбирается как заказ (см. репозиторий), поэтому
                    // список перечитывается целиком, без оптимистичной правки.
                    load(showLoading = false)
                }
            }
        }
    }
}
