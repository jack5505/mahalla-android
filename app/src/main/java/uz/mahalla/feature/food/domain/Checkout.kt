package uz.mahalla.feature.food.domain

/**
 * Как заказ попадает к человеку (эпик 5.3). Значения — перечисление бэкенда
 * `Fulfillment` (`DELIVERY`, `PICKUP`, `DINE_IN`).
 */
enum class DeliveryMethod {
    Delivery,
    Pickup,
    ;

    val apiValue: String get() = if (this == Delivery) "DELIVERY" else "PICKUP"

    companion object {
        /**
         * `DINE_IN` («на месте») в приложении отдельным способом не заведён —
         * от самовывоза он отличается только тем, что заказ не уносят: адрес
         * не нужен, доставки нет. Неизвестное значение — тоже самовывоз:
         * назвать доставкой то, чего не понял, значит нарисовать этап
         * «в пути», которого не будет.
         */
        fun fromApi(value: String?): DeliveryMethod =
            if (value?.trim().equals(Delivery.apiValue, ignoreCase = true)) Delivery else Pickup
    }
}

/**
 * Оплата. Кошелёк — основной способ по ТЗ; наличные остаются, потому что без
 * них самовывоз в первый же день упрётся в пустой баланс. Значения —
 * перечисление бэкенда `PaymentMethod` (`WALLET`, `CASH`).
 */
enum class PaymentMethod {
    Wallet,
    Cash,
    ;

    val apiValue: String get() = if (this == Wallet) "WALLET" else "CASH"

    companion object {
        fun fromApi(value: String?): PaymentMethod =
            if (value?.trim().equals(Cash.apiValue, ignoreCase = true)) Cash else Wallet
    }
}

/**
 * Форма оформления.
 *
 * Ни комментария, ни времени заказа здесь нет: `PlaceOrderRequest` бэкенда
 * принимает только заведение, позиции, способ получения, способ оплаты и
 * адрес. Поле, которое некуда отправить, обещало бы человеку, что кухня
 * прочитает его просьбу, — а она о ней не узнает.
 */
data class CheckoutForm(
    val method: DeliveryMethod = DeliveryMethod.Delivery,
    val address: String = "",
    val payment: PaymentMethod = PaymentMethod.Wallet,
) {
    val needsAddress: Boolean get() = method == DeliveryMethod.Delivery

    /** Адрес для запроса: пробелы — не адрес, а самовывозу его отправлять незачем. */
    fun addressOrNull(): String? =
        address.trim().takeIf { needsAddress && it.isNotEmpty() }
}

/** Что не так с формой. Каждая ошибка привязана к своему полю на экране. */
sealed interface CheckoutError {
    data object EmptyCart : CheckoutError
    data object AddressRequired : CheckoutError

    /** На кошельке не хватает; сколько именно — показываем, чтобы было понятно, сколько пополнять. */
    data class InsufficientFunds(val missingSum: Long) : CheckoutError
}

/**
 * Валидация checkout'а (эпик 5.3) — чистая функция от формы, итога и баланса.
 *
 * Проверяется всё сразу: подсвечивать ошибки по одной значит гонять человека
 * по форме кругами.
 */
object CheckoutValidator {

    fun validate(
        form: CheckoutForm,
        totals: CartTotals,
        cartIsEmpty: Boolean,
        walletBalanceSum: Long,
    ): List<CheckoutError> = buildList {
        if (cartIsEmpty) add(CheckoutError.EmptyCart)
        if (form.needsAddress && form.address.isBlank()) add(CheckoutError.AddressRequired)

        if (form.payment == PaymentMethod.Wallet && !cartIsEmpty) {
            val missing = totals.totalSum - walletBalanceSum
            if (missing > 0) add(CheckoutError.InsufficientFunds(missing))
        }
    }

    fun canSubmit(
        form: CheckoutForm,
        totals: CartTotals,
        cartIsEmpty: Boolean,
        walletBalanceSum: Long,
    ): Boolean = validate(form, totals, cartIsEmpty, walletBalanceSum).isEmpty()
}
