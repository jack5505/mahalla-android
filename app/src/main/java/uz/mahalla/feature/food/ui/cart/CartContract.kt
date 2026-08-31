package uz.mahalla.feature.food.ui.cart

import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.feature.food.domain.CartLine
import uz.mahalla.feature.food.domain.CartTotals
import uz.mahalla.feature.food.domain.PromoState

/**
 * Корзина (эпик 5.2).
 *
 * Итог держим отдельным полем, а не считаем в composable: сумма — единственное,
 * ради чего человек сюда заходит, и её расчёт обязан быть покрыт тестом.
 * Доставка в корзине не показывается: способ получения выбирается на
 * checkout'е, и до этого «+15 000» было бы враньём для самовывоза.
 */
data class CartState(
    val placeId: String = "",
    val placeName: String = "",
    val lines: List<CartLine> = emptyList(),
    val totals: CartTotals = CartTotals(),
    val promoInput: String = "",
    val promo: PromoState = PromoState.Idle,
    /** Черновик из Room ещё не прочитан — пустой экран пока не показываем. */
    val isLoaded: Boolean = false,
) : UiState {

    val isEmpty: Boolean get() = lines.isEmpty()

    val canCheckout: Boolean get() = lines.isNotEmpty() && promo !is PromoState.Checking

    val canApplyPromo: Boolean
        get() = promoInput.isNotBlank() && promo !is PromoState.Checking && !isEmpty

    /**
     * Показывать ли поле промокода.
     *
     * Выключено, пока `PlaceOrderRequest` бэкенда не принимает код (issue #63):
     * проверить его (`GET promotions/check`) приложение умеет, а донести до
     * заказа нечем — скидка, показанная в корзине, в счёт бы не попала. Врать
     * про деньги хуже, чем не предлагать промокод вовсе. Проверка и разбор
     * ответа остаются рабочими: как только у заказа появится поле кода, здесь
     * меняется одна константа.
     */
    val promoSupported: Boolean get() = PROMO_SUPPORTED

    private companion object {
        const val PROMO_SUPPORTED = false
    }
}

sealed interface CartEvent : UiEvent {
    data class QuantityChanged(val lineId: String, val quantity: Int) : CartEvent
    data class LineRemoved(val lineId: String) : CartEvent
    data class PromoInputChanged(val code: String) : CartEvent
    data object PromoApplied : CartEvent
    data object PromoRemoved : CartEvent
    data object CartCleared : CartEvent
    data object AddMoreClicked : CartEvent
    data object CheckoutClicked : CartEvent
    data object BackClicked : CartEvent
}

sealed interface CartEffect : UiEffect {
    data class OpenCheckout(val placeId: String) : CartEffect
    data class OpenMenu(val placeId: String) : CartEffect
    data object NavigateBack : CartEffect
}
