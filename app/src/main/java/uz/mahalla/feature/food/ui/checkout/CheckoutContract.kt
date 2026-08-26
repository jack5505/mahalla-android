package uz.mahalla.feature.food.ui.checkout

import uz.mahalla.core.result.ApiError
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.feature.food.domain.CartLine
import uz.mahalla.feature.food.domain.CartTotals
import uz.mahalla.feature.food.domain.CheckoutError
import uz.mahalla.feature.food.domain.CheckoutForm
import uz.mahalla.feature.food.domain.DeliveryMethod
import uz.mahalla.feature.food.domain.PaymentMethod
import java.time.LocalDateTime

/**
 * Оформление заказа (эпик 5.3).
 *
 * [errors] пересчитываются валидатором при каждом изменении формы и хранятся в
 * состоянии, а не считаются в composable: доступность кнопки «оформить» —
 * правило, а не деталь вёрстки.
 *
 * [validationShown] отделяет «форма ещё не заполнена» от «человек нажал и
 * ошибся»: краснеть авансом на пустом адресе не за что.
 */
data class CheckoutState(
    val placeId: String = "",
    val placeName: String = "",
    val lines: List<CartLine> = emptyList(),
    val form: CheckoutForm = CheckoutForm(),
    val totals: CartTotals = CartTotals(),
    val walletBalanceSum: Long = 0,
    val balanceKnown: Boolean = false,
    val slots: List<LocalDateTime> = emptyList(),
    val errors: List<CheckoutError> = emptyList(),
    val validationShown: Boolean = false,
    val isSubmitting: Boolean = false,
    val submitError: ApiError? = null,
    val isLoaded: Boolean = false,
) : UiState {

    val isEmpty: Boolean get() = lines.isEmpty()

    val canSubmit: Boolean get() = errors.isEmpty() && !isSubmitting

    val visibleErrors: List<CheckoutError> get() = if (validationShown) errors else emptyList()

    fun error(predicate: (CheckoutError) -> Boolean): CheckoutError? =
        visibleErrors.firstOrNull(predicate)

    val insufficientFunds: CheckoutError.InsufficientFunds?
        get() = errors.filterIsInstance<CheckoutError.InsufficientFunds>().firstOrNull()
}

sealed interface CheckoutEvent : UiEvent {
    data class MethodSelected(val method: DeliveryMethod) : CheckoutEvent
    data class AddressChanged(val address: String) : CheckoutEvent
    data class CommentChanged(val comment: String) : CheckoutEvent
    data class PaymentSelected(val payment: PaymentMethod) : CheckoutEvent
    data class AsapToggled(val asap: Boolean) : CheckoutEvent
    data class SlotSelected(val at: LocalDateTime) : CheckoutEvent
    data object SubmitClicked : CheckoutEvent
    data object TopUpClicked : CheckoutEvent
    data object BackClicked : CheckoutEvent
}

sealed interface CheckoutEffect : UiEffect {
    data class OrderCreated(val orderId: String) : CheckoutEffect
    data object OpenWallet : CheckoutEffect
    data object NavigateBack : CheckoutEffect
}
