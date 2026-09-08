package uz.mahalla.feature.food.ui.cart

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.feature.food.data.CartRepository
import uz.mahalla.feature.food.domain.Cart
import uz.mahalla.feature.food.domain.CartCalculator
import uz.mahalla.navigation.CartRoute
import javax.inject.Inject

/**
 * Корзина (эпик 5.2).
 *
 * Состав корзины — источник истины в Room: экран подписан на неё и не хранит
 * собственную копию списка, поэтому «+1» из меню сразу виден и здесь.
 */
@HiltViewModel
class CartViewModel @Inject constructor(
    private val cartRepository: CartRepository,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<CartState, CartEvent, CartEffect>(CartState()) {

    private val placeId: String = savedStateHandle.toRoute<CartRoute>().placeId

    init {
        updateState { copy(placeId = placeId) }
        viewModelScope.launch {
            cartRepository.cart(placeId).collect { cart -> updateState { withCart(cart) } }
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
        // Ни скидки, ни доставки: и то и другое называет сервер при
        // оформлении — см. KDoc `CartState`.
        totals = CartCalculator.totals(cart.lines),
        isLoaded = true,
    )
}
