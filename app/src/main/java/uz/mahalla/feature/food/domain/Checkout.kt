package uz.mahalla.feature.food.domain

import java.time.LocalDateTime

/** Как заказ попадает к человеку (эпик 5.3). */
enum class DeliveryMethod {
    Delivery,
    Pickup,
    ;

    val apiValue: String get() = name.lowercase()

    companion object {
        /** Неизвестное значение — доставка: она строже по валидации (нужен адрес). */
        fun fromApi(value: String?): DeliveryMethod =
            entries.firstOrNull { it.apiValue.equals(value?.trim(), ignoreCase = true) } ?: Delivery
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

    val apiValue: String get() = if (this == Wallet) "wallet" else "cash"

    companion object {
        fun fromApi(value: String?): PaymentMethod =
            entries.firstOrNull { it.apiValue.equals(value?.trim(), ignoreCase = true) } ?: Wallet
    }
}

/**
 * Форма оформления. [scheduledAt] заполнена только когда выключено «как можно
 * скорее»: два независимых поля времени разъезжались бы между собой.
 */
data class CheckoutForm(
    val method: DeliveryMethod = DeliveryMethod.Delivery,
    val address: String = "",
    val comment: String = "",
    val payment: PaymentMethod = PaymentMethod.Wallet,
    val asap: Boolean = true,
    val scheduledAt: LocalDateTime? = null,
) {
    val needsAddress: Boolean get() = method == DeliveryMethod.Delivery
}

/** Что не так с формой. Каждая ошибка привязана к своему полю на экране. */
sealed interface CheckoutError {
    data object EmptyCart : CheckoutError
    data object AddressRequired : CheckoutError
    data object TimeRequired : CheckoutError

    /** Время в прошлом или слишком близко: кухня не успеет. */
    data class TimeTooSoon(val minLeadMinutes: Int) : CheckoutError

    /** На кошельке не хватает; сколько именно — показываем, чтобы было понятно, сколько пополнять. */
    data class InsufficientFunds(val missingSum: Long) : CheckoutError
}

/**
 * Валидация checkout'а (эпик 5.3) — чистая функция от формы, итога и баланса.
 *
 * Проверяется всё сразу: подсвечивать ошибки по одной значит гонять человека
 * по форме кругами. Время сравнивается с переданным «сейчас», а не с
 * `LocalDateTime.now()` — иначе тест на «слишком рано» невозможен.
 */
object CheckoutValidator {

    /** Минимальный запас до заказа на время — меньше кухня физически не успеет. */
    const val MIN_LEAD_MINUTES = 30

    fun validate(
        form: CheckoutForm,
        totals: CartTotals,
        cartIsEmpty: Boolean,
        walletBalanceSum: Long,
        now: LocalDateTime,
    ): List<CheckoutError> = buildList {
        if (cartIsEmpty) add(CheckoutError.EmptyCart)
        if (form.needsAddress && form.address.isBlank()) add(CheckoutError.AddressRequired)

        if (!form.asap) {
            val at = form.scheduledAt
            if (at == null) {
                add(CheckoutError.TimeRequired)
            } else if (at.isBefore(now.plusMinutes(MIN_LEAD_MINUTES.toLong()))) {
                add(CheckoutError.TimeTooSoon(MIN_LEAD_MINUTES))
            }
        }

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
        now: LocalDateTime,
    ): Boolean = validate(form, totals, cartIsEmpty, walletBalanceSum, now).isEmpty()
}

/**
 * Слоты времени для заказа «ко времени» (эпик 5.3).
 *
 * Полноценный time picker здесь лишний: выбирать минуты незачем — кухня всё
 * равно работает получасовыми окнами, — а список слотов проверяется тестом и
 * не даёт ткнуть время в прошлом.
 */
object DeliverySlots {

    const val STEP_MINUTES = 30

    /**
     * Ближайшие [count] слотов: от «сейчас + минимальный запас», округлённого
     * **вверх** до получаса. Округление вниз дало бы слот, который валидация
     * тут же и отвергнет.
     */
    fun next(
        now: LocalDateTime,
        count: Int = DEFAULT_COUNT,
        minLeadMinutes: Int = CheckoutValidator.MIN_LEAD_MINUTES,
    ): List<LocalDateTime> {
        val earliest = now.plusMinutes(minLeadMinutes.toLong()).withSecond(0).withNano(0)
        val overflow = earliest.minute % STEP_MINUTES
        val first = if (overflow == 0) earliest else earliest.plusMinutes((STEP_MINUTES - overflow).toLong())
        return (0 until count).map { index -> first.plusMinutes((index * STEP_MINUTES).toLong()) }
    }

    private const val DEFAULT_COUNT = 8
}
