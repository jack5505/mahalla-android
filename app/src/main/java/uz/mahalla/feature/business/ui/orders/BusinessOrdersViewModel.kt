package uz.mahalla.feature.business.ui.orders

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.isLoading
import uz.mahalla.feature.business.data.BusinessRepository
import uz.mahalla.feature.business.domain.BusinessOrder
import uz.mahalla.feature.business.domain.BusinessOrderFilter
import uz.mahalla.feature.business.domain.BusinessOrderPage
import uz.mahalla.feature.business.domain.BusinessOrderStatusFlow
import uz.mahalla.feature.food.domain.OrderStatus
import uz.mahalla.navigation.BusinessArgs
import javax.inject.Inject

/**
 * Входящие заказы (задача 12.3): принять, отклонить, двигать по статусам.
 *
 * Список перечитывается на каждом возврате: заказ приходит на кухню сам, без
 * участия приложения, — и показанный десять минут назад список пуст ровно
 * тогда, когда он нужнее всего.
 */
@HiltViewModel
class BusinessOrdersViewModel @Inject constructor(
    private val repository: BusinessRepository,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<BusinessOrdersState, BusinessOrdersEvent, BusinessOrdersEffect>(
    BusinessOrdersState(
        placeName = savedStateHandle.get<String>(BusinessArgs.PLACE_NAME).orEmpty(),
    ),
) {

    private val placeId: String = savedStateHandle.get<String>(BusinessArgs.PLACE_ID).orEmpty()

    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null
    private var loadedPage = 0

    init {
        load()
    }

    override fun onEvent(event: BusinessOrdersEvent) {
        when (event) {
            BusinessOrdersEvent.ScreenResumed ->
                if (!currentState.orders.isLoading && !currentState.isRefreshing) {
                    load(showLoading = false)
                }

            BusinessOrdersEvent.Refreshed -> load(showLoading = false, refreshing = true)
            BusinessOrdersEvent.Retry -> load()
            BusinessOrdersEvent.LoadMore -> loadMore()
            is BusinessOrdersEvent.FilterSelected -> selectFilter(event.filter)
            is BusinessOrdersEvent.StatusSelected -> updateStatus(event.orderId, event.status)
        }
    }

    /**
     * Смена вкладки показывает скелетон, а не оставляет чужие заказы: список
     * «новых» под заголовком «готовые» читался бы как ответ сервера.
     */
    private fun selectFilter(filter: BusinessOrderFilter) {
        if (filter == currentState.filter) return
        updateState { copy(filter = filter) }
        load()
    }

    /**
     * Предыдущая загрузка отменяется: «повторить» поверх pull-to-refresh иначе
     * даёт два параллельных запроса, и выигрывает ответивший последним — то
     * есть возможен откат к более старому списку (нашло ревью).
     */
    private fun load(showLoading: Boolean = true, refreshing: Boolean = false) {
        loadJob?.cancel()
        loadMoreJob?.cancel()
        loadedPage = 0
        updateState {
            copy(
                orders = if (showLoading) ScreenState.Loading else orders,
                isRefreshing = refreshing,
                isLoadingMore = false,
                loadMoreFailure = null,
                actionFailure = null,
            )
        }
        val filter = currentState.filter
        loadJob = viewModelScope.launch {
            val result = repository.orders(placeId = placeId, status = filter.apiValue, page = 0)
            // Пока шёл запрос, вкладку могли переключить — ответ на прежний
            // фильтр перезаписал бы её список чужими заказами.
            if (currentState.filter != filter) return@launch
            applyPage(result)
            if (refreshing) updateState { copy(isRefreshing = false) }
        }
    }

    private fun applyPage(result: ApiResult<BusinessOrderPage>) {
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
     * Догрузка страницы. Номер считается локально: сервер, не вернувший
     * `page`, отдаёт дефолтный `0`, и «следующей» навсегда осталась бы первая
     * (то же правило, что в «моих заведениях», issue #94).
     */
    private fun loadMore() {
        val state = currentState
        if (!state.hasMore || state.isLoadingMore) return
        if (state.orders !is ScreenState.Content) return
        if (loadMoreJob?.isActive == true) return

        val nextPage = loadedPage + 1
        val filter = state.filter
        updateState { copy(isLoadingMore = true, loadMoreFailure = null) }
        loadMoreJob = viewModelScope.launch {
            val result = repository.orders(
                placeId = placeId,
                status = filter.apiValue,
                page = nextPage,
            )
            if (currentState.filter != filter) return@launch
            when (result) {
                is ApiResult.Failure -> updateState {
                    copy(isLoadingMore = false, loadMoreFailure = result.failure)
                }

                is ApiResult.Success -> {
                    loadedPage = nextPage
                    // Список берётся **сейчас**, а не снимком до запроса: пока
                    // страница ехала, кухня могла сменить статус заказа, и
                    // устаревший снимок вернул бы его в «новые» (нашло ревью).
                    val current = (currentState.orders as? ScreenState.Content)?.data.orEmpty()
                    val merged = appended(current, result.data.items)
                    updateState {
                        copy(
                            orders = ScreenState.Content(merged),
                            // Страница целиком из дубликатов список не растит,
                            // а автотриггер догрузки висит на его длине — без
                            // этого спиннер в хвосте остался бы навсегда
                            // (нашло ревью).
                            hasMore = result.data.hasMore && merged.size > current.size,
                            isLoadingMore = false,
                        )
                    }
                }
            }
        }
    }

    /**
     * Заказ может приехать на двух соседних страницах, если список изменился
     * между запросами. В `LazyColumn` это дубликат ключа и падение.
     */
    private fun appended(
        current: List<BusinessOrder>,
        next: List<BusinessOrder>,
    ): List<BusinessOrder> {
        val known = current.mapTo(mutableSetOf(), BusinessOrder::id)
        return current + next.filter { known.add(it.id) }
    }

    /**
     * Смена статуса.
     *
     * Переход проверяется **до** запроса: экран рисует только разрешённые
     * кнопки, но событие может прийти на устаревший список — заказ успели
     * отменить, пока кухня тянулась к «готово». Отправить такой запрос значит
     * показать отказ сервера там, где приложение всё знало само.
     *
     * После успеха заказ правится на месте, **но остаётся в списке даже когда
     * выпадает из фильтра**: строка, исчезнувшая ровно в момент нажатия,
     * читается как «нажал не туда». Из выборки он уйдёт при следующем
     * обновлении — то есть тогда, когда это уже не выглядит потерей.
     */
    private fun updateStatus(orderId: String, status: OrderStatus) {
        val state = currentState
        if (state.isBusy) return
        val order = orderOrNull(orderId) ?: return
        if (!BusinessOrderStatusFlow.isAllowed(order.status, status, order.method)) return

        updateState { copy(pendingOrderId = orderId, actionFailure = null) }
        viewModelScope.launch {
            val result = repository.updateOrderStatus(
                placeId = placeId,
                orderId = orderId,
                status = status,
            )
            when (result) {
                is ApiResult.Failure -> updateState {
                    copy(pendingOrderId = null, actionFailure = result.failure)
                }

                is ApiResult.Success -> {
                    updateState { copy(orders = replaced(result.data), pendingOrderId = null) }
                    emitEffect(
                        BusinessOrdersEffect.StatusChanged(
                            status = result.data.status,
                            number = result.data.number ?: order.number,
                        ),
                    )
                }
            }
        }
    }

    private fun replaced(updated: BusinessOrder): ScreenState<List<BusinessOrder>> {
        val content = currentState.orders as? ScreenState.Content ?: return currentState.orders
        return ScreenState.Content(content.data.map { if (it.id == updated.id) updated else it })
    }

    private fun orderOrNull(orderId: String): BusinessOrder? =
        (currentState.orders as? ScreenState.Content)?.data?.firstOrNull { it.id == orderId }
}
