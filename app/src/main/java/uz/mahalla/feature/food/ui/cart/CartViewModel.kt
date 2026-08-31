package uz.mahalla.feature.food.ui.cart

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.feature.food.data.CartRepository
import uz.mahalla.feature.food.data.MenuRepository
import uz.mahalla.feature.food.domain.Cart
import uz.mahalla.feature.food.domain.CartCalculator
import uz.mahalla.feature.food.domain.PromoFailure
import uz.mahalla.feature.food.domain.PromoState
import uz.mahalla.feature.food.domain.asPromoFailure
import uz.mahalla.navigation.CartRoute
import javax.inject.Inject

/**
 * Корзина (эпик 5.2).
 *
 * Состав корзины — источник истины в Room: экран подписан на неё и не хранит
 * собственную копию списка, поэтому «+1» из меню сразу виден и здесь.
 *
 * Промокод проверяет сервер: скидку в итоге выставляет он, и посчитанная
 * локально «−20 %» разошлась бы с чеком на первом же коде с ограничениями.
 */
@HiltViewModel
class CartViewModel @Inject constructor(
    private val cartRepository: CartRepository,
    private val menuRepository: MenuRepository,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<CartState, CartEvent, CartEffect>(CartState()) {

    private val placeId: String = savedStateHandle.toRoute<CartRoute>().placeId

    init {
        updateState { copy(placeId = placeId) }
        viewModelScope.launch {
            cartRepository.cart(placeId).collect(::onCart)
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

            is CartEvent.PromoInputChanged -> updateState {
                // Ошибка прошлой попытки исчезает, как только код начали
                // править: красная надпись под изменённым полем относится уже
                // не к тому, что в нём написано.
                copy(promoInput = event.code, promo = promo.resetIfRejected())
            }

            CartEvent.PromoApplied -> applyPromo()

            CartEvent.PromoRemoved -> {
                cartRepository.applyPromo(null)
                updateState { copy(promo = PromoState.Idle, promoInput = "") }
            }

            CartEvent.CartCleared -> viewModelScope.launch { cartRepository.clear(placeId) }

            CartEvent.AddMoreClicked -> emitEffect(CartEffect.OpenMenu(placeId))
            CartEvent.CheckoutClicked -> if (currentState.canCheckout) {
                emitEffect(CartEffect.OpenCheckout(placeId))
            }

            CartEvent.BackClicked -> emitEffect(CartEffect.NavigateBack)
        }
    }

    /**
     * Скидка, посчитанная сервером, относится к тому составу корзины, с которым
     * код проверяли. Изменили состав — прежний ответ снимается: показать старую
     * скидку на новой сумме значит назвать число, которого не будет в счёте.
     */
    private fun onCart(cart: Cart) {
        val stale = cart.promo?.isStaleFor(CartCalculator.subtotal(cart.lines)) == true
        if (stale) {
            cartRepository.applyPromo(null)
            updateState { copy(promo = PromoState.Idle) }
        }
        updateState { withCart(cart) }
    }

    private fun CartState.withCart(cart: Cart): CartState = copy(
        placeName = cart.placeName.takeIf(String::isNotBlank) ?: placeName,
        lines = cart.lines,
        // Доставка добавится на checkout'е, когда станет известен способ.
        totals = CartCalculator.totals(cart, deliverySum = 0),
        promo = cart.promo?.let(PromoState::Applied) ?: promo.clearedIfApplied(),
        isLoaded = true,
    )

    /**
     * Проверка промокода. Сумма уходит на сервер вместе с кодом: скидку считает
     * он, и «минимальный заказ 100 000» проверяется по актуальному составу, а не
     * по тому, что было в корзине, когда код вводили.
     */
    private fun applyPromo() {
        val state = currentState
        if (!state.canApplyPromo) return

        val code = state.promoInput.trim()
        val subtotal = state.totals.subtotalSum
        updateState { copy(promo = PromoState.Checking) }
        viewModelScope.launch {
            when (val result = menuRepository.promo(placeId, code, subtotal)) {
                is ApiResult.Failure -> updateState {
                    copy(promo = PromoState.Rejected(result.failure.asPromoFailure()))
                }

                // `null` — код есть, но к этому заказу не применяется: молча
                // показать скидку 0 значит оставить человека без объяснения.
                is ApiResult.Success -> when (val promo = result.data) {
                    null -> updateState {
                        copy(promo = PromoState.Rejected(PromoFailure.NotApplicable))
                    }

                    else -> cartRepository.applyPromo(promo)
                }
            }
        }
    }

    private fun PromoState.resetIfRejected(): PromoState =
        if (this is PromoState.Rejected) PromoState.Idle else this

    private fun PromoState.clearedIfApplied(): PromoState =
        if (this is PromoState.Applied) PromoState.Idle else this
}
