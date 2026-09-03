package uz.mahalla.feature.food.ui.cart

import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.feature.food.domain.CartLine
import uz.mahalla.feature.food.domain.CartTotals

/**
 * Корзина (эпик 5.2).
 *
 * Итог держим отдельным полем, а не считаем в composable: сумма — единственное,
 * ради чего человек сюда заходит, и её расчёт обязан быть покрыт тестом.
 * Ни доставки, ни скидки в корзине нет: стоимость доставки бэкенд сообщает
 * только в ответе о заказе, а промокод к заказу приложить нечем (см.
 * `MenuRepository`) — «−20 %» на экране разошлось бы со счётом.
 */
data class CartState(
    val placeId: String = "",
    val placeName: String = "",
    val lines: List<CartLine> = emptyList(),
    val totals: CartTotals = CartTotals(),
    /** Черновик из Room ещё не прочитан — пустой экран пока не показываем. */
    val isLoaded: Boolean = false,
) : UiState {

    val isEmpty: Boolean get() = lines.isEmpty()

    val canCheckout: Boolean get() = lines.isNotEmpty()
}

sealed interface CartEvent : UiEvent {
    data class QuantityChanged(val lineId: String, val quantity: Int) : CartEvent
    data class LineRemoved(val lineId: String) : CartEvent
    data object CartCleared : CartEvent
    data object AddMoreClicked : CartEvent
    data object CheckoutClicked : CartEvent
    data object BackClicked : CartEvent
}

sealed interface CartEffect : UiEffect {
    data class OpenCheckout(val placeId: String) : CartEffect
    data class OpenMenu(val placeId: String, val placeName: String) : CartEffect
    data object NavigateBack : CartEffect
}
