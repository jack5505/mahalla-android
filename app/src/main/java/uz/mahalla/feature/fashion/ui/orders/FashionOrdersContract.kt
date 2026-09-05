package uz.mahalla.feature.fashion.ui.orders

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.food.domain.Order

/**
 * «Мои заказы одежды» (issue #108).
 *
 * @param confirmCancel заказ, отмену которого человек подтверждает. Хранится
 * целиком: диалог называет номер и сумму, а искать их в списке ради подписи —
 * лишний повод разойтись с тем, что нажали.
 * @param pendingCancelId строка, по которой идёт отмена: пока она висит,
 * остальные заблокированы — ответы приезжали бы на список, которого уже нет.
 * @param cancelFailure отказ отмены — отдельно от [orders]: список уже на
 * экране, и прятать его из-за неудавшейся кнопки незачем.
 */
data class FashionOrdersState(
    val orders: ScreenState<List<Order>> = ScreenState.Loading,
    val isRefreshing: Boolean = false,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadMoreFailure: ApiFailure? = null,
    val confirmCancel: Order? = null,
    val pendingCancelId: String? = null,
    val cancelFailure: ApiFailure? = null,
) : UiState

sealed interface FashionOrdersEvent : UiEvent {
    /**
     * Экран вернулся на передний план: статус двигает магазин, и показанное
     * час назад «принят» ничего не стоит.
     */
    data object ScreenResumed : FashionOrdersEvent

    data object Refreshed : FashionOrdersEvent
    data object Retry : FashionOrdersEvent
    data object LoadMore : FashionOrdersEvent

    data class CancelRequested(val orderId: String) : FashionOrdersEvent
    data object CancelDismissed : FashionOrdersEvent
    data object CancelConfirmed : FashionOrdersEvent
}

/** Переходов наружу у экрана нет: «назад» ведёт туда, откуда его открыли. */
sealed interface FashionOrdersEffect : UiEffect
