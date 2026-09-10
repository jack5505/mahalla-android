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
 *
 * Доставку до оформления называет `food/delivery-fee` (issue #179) — в корзине
 * это оценка при доставке, способ получения человек выбирает следующим экраном.
 * Скидки нет: промокод к заказу «Еды» приложить нечем (см. `MenuRepository`) —
 * «−20 %» на экране разошлось бы со счётом.
 */
data class CartState(
    val placeId: String = "",
    val placeName: String = "",
    val lines: List<CartLine> = emptyList(),
    val totals: CartTotals = CartTotals(),
    /**
     * Стоимость доставки; `null` — неизвестна (не ответила или сервер не
     * назвал её). Тогда экран показывает одну строку итога, как до issue #179,
     * а не ноль в строке «Доставка»: ноль читался бы как «бесплатно».
     */
    val deliverySum: Long? = null,
    /** Черновик из Room ещё не прочитан — пустой экран пока не показываем. */
    val isLoaded: Boolean = false,
) : UiState {

    val isEmpty: Boolean get() = lines.isEmpty()

    val canCheckout: Boolean get() = lines.isNotEmpty()

    /**
     * Показывать ли разбивку «позиции + доставка + итого». Бесплатную доставку
     * (`0`) отдельной строкой не рисуем — три числа, из которых одно ноль,
     * читаются как ошибка расчёта.
     */
    val showsDelivery: Boolean get() = (deliverySum ?: 0) > 0 && lines.isNotEmpty()
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
