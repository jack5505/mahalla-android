package uz.mahalla.feature.freelancer.ui.orders

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.freelancer.domain.FreelancerOrder

/**
 * «Мои заказы у мастеров» (issue #107).
 *
 * Список только читается: отменить заказ клиенту нечем — статус меняет сам
 * мастер (`PUT freelancers/orders/{orderId}/status`, его кабинет — эпик #16),
 * а клиентской отмены в контракте нет вовсе. Кнопки, которая кончится
 * отказом, здесь поэтому нет.
 */
data class MyFreelancerOrdersState(
    val orders: ScreenState<List<FreelancerOrder>> = ScreenState.Loading,
    val isRefreshing: Boolean = false,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadMoreFailure: ApiFailure? = null,
) : UiState

sealed interface MyFreelancerOrdersEvent : UiEvent {
    /**
     * Экран вернулся на передний план: мастер мог взяться за заказ или
     * отказаться, пока приложение было в фоне, — а увидеть именно это сюда и
     * приходят.
     */
    data object ScreenResumed : MyFreelancerOrdersEvent

    data object Refreshed : MyFreelancerOrdersEvent
    data object Retry : MyFreelancerOrdersEvent
    data object LoadMore : MyFreelancerOrdersEvent
}

/** У экрана нет переходов наружу: «назад» ведёт туда, откуда его открыли. */
sealed interface MyFreelancerOrdersEffect : UiEffect
