package uz.mahalla.feature.food.ui.checkout

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.feature.food.domain.CartLine
import uz.mahalla.feature.food.domain.CartTotals
import uz.mahalla.feature.food.domain.CheckoutError
import uz.mahalla.feature.food.domain.CheckoutForm
import uz.mahalla.feature.food.domain.DeliveryMethod
import uz.mahalla.feature.food.domain.PaymentMethod
import uz.mahalla.feature.wallet.ui.pay.WalletPaymentState

/**
 * Оформление заказа (эпик 5.3).
 *
 * [errors] пересчитываются валидатором при каждом изменении формы и хранятся в
 * состоянии, а не считаются в composable: доступность кнопки «оформить» —
 * правило, а не деталь вёрстки.
 *
 * [validationShown] отделяет «форма ещё не заполнена» от «человек нажал и
 * ошибся»: краснеть авансом на пустом адресе не за что.
 *
 * [payment] — шторка подтверждения оплаты из кошелька (задача 8.3 эпика #12);
 * `null` — её нет. Состояние приходит из
 * [uz.mahalla.feature.wallet.ui.pay.WalletPaymentFlow] и здесь не меняется:
 * ход оплаты один на все вертикали, и своя копия правил в каждом checkout'е
 * разошлась бы с остальными на первой же правке.
 */
data class CheckoutState(
    val placeId: String = "",
    val placeName: String = "",
    val lines: List<CartLine> = emptyList(),
    val form: CheckoutForm = CheckoutForm(),
    val totals: CartTotals = CartTotals(),
    val walletBalanceSum: Long = 0,
    val balanceKnown: Boolean = false,
    val errors: List<CheckoutError> = emptyList(),
    val validationShown: Boolean = false,
    val isSubmitting: Boolean = false,
    val submitError: ApiFailure? = null,
    val isLoaded: Boolean = false,
    val payment: WalletPaymentState? = null,
) : UiState {

    val isEmpty: Boolean get() = lines.isEmpty()

    /** Пока идёт подтверждение оплаты, «оформить» нажимать не на что. */
    val canSubmit: Boolean get() = errors.isEmpty() && !isSubmitting && payment == null

    /**
     * Кнопка «оформить» показывает индикатор и пока идёт оплата кошельком —
     * но не пока шторка ждёт PIN или показывает отказ: крутящийся индикатор
     * под открытым отказом обещал бы, что что-то ещё происходит.
     */
    val isBusy: Boolean get() = isSubmitting || payment?.isBusy == true

    val visibleErrors: List<CheckoutError> get() = if (validationShown) errors else emptyList()

    fun error(predicate: (CheckoutError) -> Boolean): CheckoutError? =
        visibleErrors.firstOrNull(predicate)

    val insufficientFunds: CheckoutError.InsufficientFunds?
        get() = errors.filterIsInstance<CheckoutError.InsufficientFunds>().firstOrNull()
}

sealed interface CheckoutEvent : UiEvent {
    data class MethodSelected(val method: DeliveryMethod) : CheckoutEvent
    data class AddressChanged(val address: String) : CheckoutEvent
    data class PaymentSelected(val payment: PaymentMethod) : CheckoutEvent
    data object SubmitClicked : CheckoutEvent
    data object TopUpClicked : CheckoutEvent
    data object BackClicked : CheckoutEvent

    /** Подтверждение оплаты из кошелька (8.3) — события общей шторки. */
    data class PaymentPinChanged(val pin: String) : CheckoutEvent
    data object PaymentBiometricConfirmed : CheckoutEvent
    data object PaymentBiometricRejected : CheckoutEvent
    data object PaymentRetried : CheckoutEvent
    data object PaymentDismissed : CheckoutEvent
}

sealed interface CheckoutEffect : UiEffect {
    data class OrderCreated(val orderId: String) : CheckoutEffect
    data object OpenWallet : CheckoutEffect
    data object NavigateBack : CheckoutEffect
}
