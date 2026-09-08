package uz.mahalla.feature.fashion.ui.checkout

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.feature.fashion.domain.FashionCartItem
import uz.mahalla.feature.food.domain.CartTotals
import uz.mahalla.feature.food.domain.CheckoutError
import uz.mahalla.feature.food.domain.CheckoutForm
import uz.mahalla.feature.food.domain.DeliveryMethod
import uz.mahalla.feature.food.domain.PaymentMethod

/**
 * Оформление заказа одежды (issue #108).
 *
 * Форма и её правила — те же, что у «Еды»: у бэкенда это один и тот же
 * `PlaceOrderRequest` (способ получения, оплата, адрес), и вторая копия
 * валидатора разошлась бы с первой.
 *
 * @param items строки **одного магазина**: серверная корзина общая, а заказ
 * оформляется по одному `placeId` за раз.
 * @param orderCreated заказ создан. Экран не уходит сам: молчаливый переход
 * читается как «ничего не произошло» (issue #49) — показывается
 * подтверждение с путём в «мои заказы».
 * @param validationShown отделяет «форма ещё не заполнена» от «человек нажал и
 * ошибся»: краснеть авансом на пустом адресе не за что.
 */
data class FashionCheckoutState(
    val storeId: String = "",
    val items: List<FashionCartItem> = emptyList(),
    val isLoaded: Boolean = false,
    val loadFailure: ApiFailure? = null,
    val form: CheckoutForm = CheckoutForm(),
    val totals: CartTotals = CartTotals(),
    val walletBalanceSum: Long = 0,
    val balanceKnown: Boolean = false,
    val errors: List<CheckoutError> = emptyList(),
    val validationShown: Boolean = false,
    val isSubmitting: Boolean = false,
    val submitError: ApiFailure? = null,
    val orderCreated: Boolean = false,
) : UiState {

    val isEmpty: Boolean get() = items.isEmpty()

    val canSubmit: Boolean get() = errors.isEmpty() && !isSubmitting && !orderCreated

    val visibleErrors: List<CheckoutError> get() = if (validationShown) errors else emptyList()

    fun error(predicate: (CheckoutError) -> Boolean): CheckoutError? =
        visibleErrors.firstOrNull(predicate)

    val insufficientFunds: CheckoutError.InsufficientFunds?
        get() = errors.filterIsInstance<CheckoutError.InsufficientFunds>().firstOrNull()
}

sealed interface FashionCheckoutEvent : UiEvent {
    data object Retry : FashionCheckoutEvent
    data class MethodSelected(val method: DeliveryMethod) : FashionCheckoutEvent
    data class AddressChanged(val address: String) : FashionCheckoutEvent
    data class PaymentSelected(val payment: PaymentMethod) : FashionCheckoutEvent
    data object SubmitClicked : FashionCheckoutEvent
    data object TopUpClicked : FashionCheckoutEvent
    data object OrdersClicked : FashionCheckoutEvent
}

sealed interface FashionCheckoutEffect : UiEffect {
    data object OpenOrders : FashionCheckoutEffect
    data object OpenWallet : FashionCheckoutEffect
}
