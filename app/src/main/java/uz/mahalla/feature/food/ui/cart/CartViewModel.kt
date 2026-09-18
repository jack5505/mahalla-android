package uz.mahalla.feature.food.ui.cart

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.feature.food.data.CartRepository
import uz.mahalla.feature.food.data.DeliveryFeeRepository
import uz.mahalla.feature.food.domain.Cart
import uz.mahalla.feature.food.domain.CartCalculator
import uz.mahalla.feature.food.ui.DeliveryFeeLoader
import uz.mahalla.navigation.CartRoute
import javax.inject.Inject

/**
 * Корзина (эпик 5.2).
 *
 * Состав корзины — источник истины в Room: экран подписан на неё и не хранит
 * собственную копию списка, поэтому «+1» из меню сразу виден и здесь.
 *
 * Стоимость доставки запрашивается у сервера по сумме позиций (issue #179):
 * до оформления это оценка, но она честнее нуля, который потом вырастет в
 * чеке. Способ получения выбирается на следующем экране, поэтому здесь
 * доставка считается нужной — по умолчанию заказ доставляют
 * (`CheckoutForm.method`).
 */
@HiltViewModel
class CartViewModel @Inject constructor(
    private val cartRepository: CartRepository,
    deliveryFeeRepository: DeliveryFeeRepository,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<CartState, CartEvent, CartEffect>(CartState()) {

    private val placeId: String = savedStateHandle.toRoute<CartRoute>().placeId

    private val deliveryFee = DeliveryFeeLoader(
        repository = deliveryFeeRepository,
        scope = viewModelScope,
        onFee = { fee -> updateState { withDeliveryFee(fee) } },
    )

    init {
        updateState { copy(placeId = placeId) }
        viewModelScope.launch {
            cartRepository.cart(placeId).collect { cart ->
                updateState { withCart(cart) }
                deliveryFee.refresh(
                    itemsSum = currentState.totals.subtotalSum,
                    needed = true,
                )
            }
        }
    }

    override fun onEvent(event: CartEvent) {
        when (event) {
            is CartEvent.QuantityChanged -> viewModelScope.launch {
                cartRepository.setQuantity(placeId, event.lineId, event.quantity)
            }

            is CartEvent.LineRemoved -> viewModelScope.launch {
                cartRepository.remove(placeId, event.lineId)
            }

            CartEvent.CartCleared -> viewModelScope.launch { cartRepository.clear(placeId) }

            CartEvent.AddMoreClicked -> emitEffect(
                CartEffect.OpenMenu(placeId, currentState.placeName),
            )

            CartEvent.CheckoutClicked -> if (currentState.canCheckout) {
                emitEffect(CartEffect.OpenCheckout(placeId))
            }

            CartEvent.BackClicked -> emitEffect(CartEffect.NavigateBack)
        }
    }

    private fun CartState.withCart(cart: Cart): CartState = copy(
        placeName = cart.placeName.takeIf(String::isNotBlank) ?: placeName,
        lines = cart.lines,
        isLoaded = true,
    ).recalculated()

    private fun CartState.withDeliveryFee(fee: Long?): CartState =
        copy(deliverySum = fee).recalculated()

    /**
     * Итог — одним расчётом на всё состояние, как в чекауте: считать его в
     * каждом редьюсере по отдельности значит однажды забыть про доставку в
     * третьем.
     *
     * Скидки в корзине нет — промокод к заказу «Еды» приложить нечем
     * (см. `MenuRepository`).
     */
    private fun CartState.recalculated(): CartState =
        copy(totals = CartCalculator.totals(lines, deliverySum = deliverySum ?: 0))
}
