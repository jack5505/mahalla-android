package uz.mahalla.feature.freelancer.ui.orders

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.freelancer.domain.FreelancerOrder
import uz.mahalla.feature.freelancer.domain.FreelancerOrderStatus

/**
 * «Мои заказы у мастеров» (issue #107).
 *
 * Список только читается: отменить заказ клиенту нечем — статус меняет сам
 * мастер, из своего экрана входящих заказов
 * ([uz.mahalla.feature.freelancer.ui.orders.MyFreelancerIncomingOrdersScreen],
 * issue #190), а клиентской отмены в контракте нет вовсе. Кнопки, которая
 * кончится отказом, здесь поэтому нет.
 */
data class MyFreelancerOrdersState(
    val orders: ScreenState<List<FreelancerOrder>> = ScreenState.Loading,
    val isRefreshing: Boolean = false,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadMoreFailure: ApiFailure? = null,
) : UiState {

    /**
     * Пояснение «отменить нечем» (issue #436) показывается, только пока есть
     * заказ, который ещё может понадобиться отменить: завершённый или
     * отклонённый уже не нуждается в предупреждении, а на пустом списке
     * отменять нечего. [FreelancerOrderStatus.isFinal] не считает
     * [FreelancerOrderStatus.Unknown] финальным по той же причине, что и
     * здесь — незнакомое значение не повод молчать о заказе, который может
     * быть ещё в силе.
     */
    val showCancelNote: Boolean
        get() = (orders as? ScreenState.Content)?.data.orEmpty().any { !it.status.isFinal }
}

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
