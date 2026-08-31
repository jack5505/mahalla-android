package uz.mahalla.feature.food.domain

/**
 * Как заказ попадает к человеку (эпик 5.3).
 *
 * Значения — перечисление бэкенда (issue #63). `DINE_IN` («поем на месте») у
 * сервера есть, но отдельным способом в приложении не заводится: для человека
 * это тот же «прийти и забрать», и третья кнопка в переключателе объясняла бы
 * разницу, которой в оформлении нет.
 */
enum class DeliveryMethod {
    Delivery,
    Pickup,
    ;

    val apiValue: String get() = if (this == Delivery) "DELIVERY" else "PICKUP"

    companion object {
        /** Неизвестное значение — доставка: она строже по валидации (нужен адрес). */
        fun fromApi(value: String?): DeliveryMethod {
            val normalized = value?.trim()?.uppercase() ?: return Delivery
            return when (normalized) {
                "PICKUP", "DINE_IN" -> Pickup
                else -> Delivery
            }
        }
    }
}

/**
 * Оплата. Кошелёк — основной способ по ТЗ; наличные остаются, потому что без
 * них самовывоз в первый же день упрётся в пустой баланс.
 */
enum class PaymentMethod {
    Wallet,
    Cash,
    ;

    val apiValue: String get() = if (this == Wallet) "WALLET" else "CASH"

    companion object {
        fun fromApi(value: String?): PaymentMethod =
            entries.firstOrNull { it.apiValue.equals(value?.trim(), ignoreCase = true) } ?: Wallet
    }
}

/**
 * Форма оформления.
 *
 * Времени доставки и комментария здесь больше нет: `PlaceOrderRequest`
 * бэкенда принимает только заведение, позиции, способ получения, способ оплаты
 * и адрес (issue #63). Спрашивать время, которое молча выбрасывается по дороге
 * на сервер, — обещание, которого никто не выполнит.
 */
data class CheckoutForm(
    val method: DeliveryMethod = DeliveryMethod.Delivery,
    val address: String = "",
    val payment: PaymentMethod = PaymentMethod.Wallet,
) {
    val needsAddress: Boolean get() = method == DeliveryMethod.Delivery
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
