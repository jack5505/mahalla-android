package uz.mahalla.feature.business.ui.orders

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.business.domain.BusinessOrder
import uz.mahalla.feature.business.domain.BusinessOrderFilter
import uz.mahalla.feature.business.domain.BusinessOrderStatusFlow
import uz.mahalla.feature.food.domain.OrderStatus

/**
 * Состояние входящих заказов (задача 12.3).
 *
 * @param filter выбранная вкладка. Смена вкладки — новый запрос, а не
 * локальная фильтрация: `GET food/places/{id}/orders` принимает `status` и
 * пагинирован, и отфильтровать страницу на клиенте значило бы показать «пусто»
 * там, где нужные заказы просто лежат на второй странице.
 * @param pendingOrderId заказ, по которому идёт смена статуса.
 */
data class BusinessOrdersState(
    val placeName: String = "",
    val filter: BusinessOrderFilter = BusinessOrderFilter.All,
    val orders: ScreenState<List<BusinessOrder>> = ScreenState.Loading,
    val isRefreshing: Boolean = false,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val pendingOrderId: String? = null,
    val actionFailure: ApiFailure? = null,
    val loadMoreFailure: ApiFailure? = null,
) : UiState {

    /**
     * Сколько заказов ждёт ответа кухни. Показывается в шапке: на вкладке
     * «все» это единственный способ увидеть, что новые вообще есть.
     */
    val newCount: Int
        get() = (orders as? ScreenState.Content)?.data
            ?.count { BusinessOrderStatusFlow.isNew(it.status) }
            ?: 0

    val isBusy: Boolean get() = pendingOrderId != null
}

sealed interface BusinessOrdersEvent : UiEvent {
    /** Заказы приходят, пока экран в фоне, — ради них сюда и возвращаются. */
    data object ScreenResumed : BusinessOrdersEvent

    data object Refreshed : BusinessOrdersEvent
    data object Retry : BusinessOrdersEvent
    data object LoadMore : BusinessOrdersEvent
    data class FilterSelected(val filter: BusinessOrderFilter) : BusinessOrdersEvent

    data class StatusSelected(val orderId: String, val status: OrderStatus) : BusinessOrdersEvent
}

sealed interface BusinessOrdersEffect : UiEffect {
    /**
     * Статус сменён. Едет сам статус, а не готовая строка: подписи живут в
     * ресурсах и собираются в Compose.
     */
    data class StatusChanged(val status: OrderStatus, val number: String?) : BusinessOrdersEffect
}
