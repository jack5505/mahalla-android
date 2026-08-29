package uz.mahalla.feature.food.ui.order

import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.food.domain.Order
import uz.mahalla.feature.food.domain.OrderStatus
import uz.mahalla.feature.food.domain.OrderStatusFlow

/**
 * Статус заказа (эпик 5.4).
 *
 * Этапы считаются доменом от способа получения: у самовывоза нет доставки, у
 * доставки нет «готово к выдаче».
 */
data class OrderStatusState(
    val order: ScreenState<Order> = ScreenState.Loading,
    val isCancelling: Boolean = false,
    val cancelConfirmVisible: Boolean = false,
    /** Отмена не прошла — сообщение живёт до следующего действия. */
    val cancelFailed: Boolean = false,
    val isRepeating: Boolean = false,
    /** Корзину собрать не удалось — заказ остался, но идти в корзину незачем. */
    val repeatFailed: Boolean = false,
) : UiState {

    val data: Order? get() = (order as? ScreenState.Content)?.data

    val stages: List<OrderStatus>
        get() = data?.let { OrderStatusFlow.stages(it.method) }.orEmpty()

    val canCancel: Boolean
        get() = data?.let { OrderStatusFlow.canCancel(it.status) } == true && !isCancelling

    val canRepeat: Boolean
        get() = data?.let { OrderStatusFlow.canRepeat(it.status) } == true && !isRepeating
}

sealed interface OrderStatusEvent : UiEvent {
    data object Retry : OrderStatusEvent

    /** Экран виден — опрос идёт. Ушёл в фон — опрашивать некому и незачем. */
    data object ScreenStarted : OrderStatusEvent
    data object ScreenStopped : OrderStatusEvent

    data object CancelClicked : OrderStatusEvent
    data object CancelConfirmed : OrderStatusEvent
    data object CancelDismissed : OrderStatusEvent
    data object RepeatClicked : OrderStatusEvent
    data object BackClicked : OrderStatusEvent
}

sealed interface OrderStatusEffect : UiEffect {
    /** Повтор собрал корзину заново — дальше человек идёт в неё. */
    data class OpenCart(val placeId: String) : OrderStatusEffect
    data object NavigateBack : OrderStatusEffect
}
