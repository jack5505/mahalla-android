package uz.mahalla.feature.fashion.ui.orders

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.isLoading
import uz.mahalla.feature.fashion.data.FashionOrderRepository
import uz.mahalla.feature.fashion.domain.FashionOrderPage
import uz.mahalla.feature.food.domain.Order
import uz.mahalla.feature.food.domain.OrderStatus
import uz.mahalla.feature.food.domain.OrderStatusFlow
import javax.inject.Inject

/**
 * «Мои заказы одежды» (issue #108): список страницами и отмена.
 *
 * Список читается общим `GET orders?vertical=CLOTHING` — у `fashion/orders/my`
 * схема ответа перекрыта коллизией springdoc (см. `FashionApi`).
 *
 * Опроса статуса нет: его двигает магазин, и постоянный опрос имел бы смысл
 * только у одного заказа. О решении магазина сообщают уведомления (issue
 * #81), а список перечитывается на каждом возврате на экран.
 */
@HiltViewModel
class FashionOrdersViewModel @Inject constructor(
    private val repository: FashionOrderRepository,
) : MviViewModel<FashionOrdersState, FashionOrdersEvent, FashionOrdersEffect>(
    FashionOrdersState(),
) {

    private var loadMoreJob: Job? = null
    private var loadedPage = 0

    init {
        load()
    }

    override fun onEvent(event: FashionOrdersEvent) {
        when (event) {
            // Пока идёт загрузка, перезапрашивать нечего: ответ приедет на уже
            // сменившееся состояние.
            FashionOrdersEvent.ScreenResumed -> {
                val state = currentState
                if (!state.orders.isLoading && !state.isRefreshing) load(showLoading = false)
            }

            FashionOrdersEvent.Refreshed -> load(showLoading = false, refreshing = true)
            FashionOrdersEvent.Retry -> load()
            FashionOrdersEvent.LoadMore -> loadMore()

            is FashionOrdersEvent.CancelRequested -> updateState {
                copy(
                    confirmCancel = orderOrNull(event.orderId)
                        ?.takeIf { OrderStatusFlow.canCancel(it.status) },
                    cancelFailure = null,
                )
            }

            FashionOrdersEvent.CancelDismissed -> updateState { copy(confirmCancel = null) }
            FashionOrdersEvent.CancelConfirmed -> cancel()
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
                cancelFailure = null,
            )
        }
        viewModelScope.launch {
            applyPage(repository.myOrders(page = 0))
            if (refreshing) updateState { copy(isRefreshing = false) }
        }
    }

    private fun applyPage(result: ApiResult<FashionOrderPage>) {
        when (result) {
            is ApiResult.Failure -> updateState {
                copy(orders = ScreenState.Error(result.failure), hasMore = false)
            }

            is ApiResult.Success -> updateState {
                copy(hasMore = result.data.hasMore).withOrders(result.data.items)
            }
        }
    }

    /**
     * Догрузка. Провал не стирает показанные заказы, но и дёргать сеть в цикле
     * нельзя: список не вырос, автотриггер по концу больше не сработает.
     *
     * Номер загруженной страницы считается локально: сервер, не вернувший
     * `page`, отдаёт дефолтный `0`, и «следующей» навсегда осталась бы первая.
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
                        copy(hasMore = result.data.hasMore, isLoadingMore = false)
                            .withOrders(appended(loaded.data, result.data.items))
                    }
                }
            }
        }
    }

    /**
     * Заказ может приехать на двух соседних страницах, если список изменился
     * между запросами. В `LazyColumn` это дубликат ключа и падение.
     */
    private fun appended(current: List<Order>, next: List<Order>): List<Order> {
        val known = current.mapTo(mutableSetOf(), Order::id)
        return current + next.filter { known.add(it.id) }
    }

    /**
     * Отмена. Новое состояние **перечитывается у сервера**, а не сочиняется
     * на клиенте: ответ `POST fashion/orders/{id}/cancel` описан перекрытой
     * коллизией схемой, и разбирать из него статус — гадание. Не удалось
     * перечитать — заказ помечается отменённым локально: сервер отмену уже
     * подтвердил, и оставить строку в прежнем виде значило бы предложить
     * отменить её второй раз.
     */
    private fun cancel() {
        val order = currentState.confirmCancel ?: return
        if (currentState.pendingCancelId != null) return

        updateState { copy(confirmCancel = null, pendingCancelId = order.id, cancelFailure = null) }
        viewModelScope.launch {
            when (val result = repository.cancel(order.id)) {
                is ApiResult.Failure -> updateState {
                    copy(pendingCancelId = null, cancelFailure = result.failure)
                }

                is ApiResult.Success -> {
                    val updated = (repository.order(order.id) as? ApiResult.Success)?.data
                        ?: order.copy(status = OrderStatus.Cancelled)
                    updateState {
                        copy(pendingCancelId = null).withOrders(
                            ordersOrEmpty().map { if (it.id == updated.id) updated else it },
                        )
                    }
                }
            }
        }
    }

    /**
     * Отменённый заказ из списка не пропадает — он остаётся с новым статусом:
     * исчезнуть без следа значило бы оставить человека в сомнении, отменилось
     * ли что-нибудь вообще.
     */
    private fun FashionOrdersState.withOrders(items: List<Order>): FashionOrdersState = copy(
        orders = if (items.isEmpty()) ScreenState.Empty else ScreenState.Content(items),
    )

    private fun ordersOrEmpty(): List<Order> =
        (currentState.orders as? ScreenState.Content)?.data.orEmpty()

    private fun orderOrNull(id: String): Order? = ordersOrEmpty().firstOrNull { it.id == id }
}
