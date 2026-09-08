package uz.mahalla.feature.food.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Валидация оформления (эпик 5.3).
 *
 * Времени заказа в форме нет: `PlaceOrderRequest` бэкенда его не принимает —
 * см. KDoc [CheckoutForm].
 */
class CheckoutValidatorTest {

    private val totals = CartTotals(subtotalSum = 50_000)

    @Test
    fun `delivery without an address is not allowed`() {
        val errors = validate(CheckoutForm(method = DeliveryMethod.Delivery, address = "  "))

        assertTrue(errors.contains(CheckoutError.AddressRequired))
    }

    @Test
    fun `pickup needs no address`() {
        val errors = validate(CheckoutForm(method = DeliveryMethod.Pickup, address = ""))

        assertFalse(errors.contains(CheckoutError.AddressRequired))
    }

    @Test
    fun `pickup sends no address even when one was typed`() {
        // Адрес самовывоза сервер не спрашивает, а присланный он мог бы
        // принять за адрес доставки.
        val form = CheckoutForm(method = DeliveryMethod.Pickup, address = "Amir Temur 1")

        assertNull(form.addressOrNull())
    }

    @Test
    fun `delivery address is trimmed`() {
        val form = CheckoutForm(method = DeliveryMethod.Delivery, address = "  Navoiy 5  ")

        assertEquals("Navoiy 5", form.addressOrNull())
    }

    @Test
    fun `wallet payment reports exactly how much is missing`() {
        val errors = validate(pickup(), walletBalanceSum = 30_000)

        assertEquals(listOf(CheckoutError.InsufficientFunds(20_000)), errors)
    }

    @Test
    fun `cash payment does not look at the wallet`() {
        val errors = validate(pickup().copy(payment = PaymentMethod.Cash), walletBalanceSum = 0)

        assertTrue(errors.isEmpty())
    }

    @Test
    fun `an empty cart cannot be submitted and does not claim missing funds`() {
        // «Не хватает 0 сум» на пустой корзине — бессмысленная ошибка.
        val errors = validate(pickup(), cartIsEmpty = true, walletBalanceSum = 0)

        assertEquals(listOf(CheckoutError.EmptyCart), errors)
    }

    @Test
    fun `every problem is reported at once`() {
        val form = CheckoutForm(method = DeliveryMethod.Delivery, address = "")

        val errors = validate(form, walletBalanceSum = 0)

        assertEquals(2, errors.size)
        assertFalse(CheckoutValidator.canSubmit(form, totals, false, 0))
    }

    @Test
    fun `a filled form with money on the wallet passes`() {
        val form = CheckoutForm(method = DeliveryMethod.Delivery, address = "Navoiy 5")

        assertTrue(CheckoutValidator.canSubmit(form, totals, false, 50_000))
    }

    private fun pickup() = CheckoutForm(method = DeliveryMethod.Pickup)

    private fun validate(
        form: CheckoutForm,
        cartIsEmpty: Boolean = false,
        walletBalanceSum: Long = 1_000_000,
    ) = CheckoutValidator.validate(
        form = form,
        totals = totals,
        cartIsEmpty = cartIsEmpty,
        walletBalanceSum = walletBalanceSum,
    )
}
