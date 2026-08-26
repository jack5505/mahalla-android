package uz.mahalla.feature.food.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Валидация оформления (эпик 5.3). «Сейчас» передаётся параметром — иначе
 * проверка «слишком рано» зависела бы от времени прогона тестов.
 */
class CheckoutValidatorTest {

    private val now: LocalDateTime = LocalDateTime.of(2026, 8, 26, 12, 0)

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
    fun `scheduled order without a time is not allowed`() {
        val errors = validate(pickup().copy(asap = false, scheduledAt = null))

        assertTrue(errors.contains(CheckoutError.TimeRequired))
    }

    @Test
    fun `a time too close to now is rejected`() {
        val errors = validate(
            pickup().copy(asap = false, scheduledAt = now.plusMinutes(10)),
        )

        assertEquals(
            listOf(CheckoutError.TimeTooSoon(CheckoutValidator.MIN_LEAD_MINUTES)),
            errors,
        )
    }

    @Test
    fun `a time with enough lead is accepted`() {
        val form = pickup().copy(
            asap = false,
            scheduledAt = now.plusMinutes(CheckoutValidator.MIN_LEAD_MINUTES.toLong()),
        )

        assertTrue(validate(form).isEmpty())
    }

    @Test
    fun `asap ignores a stale scheduled time`() {
        // Время из прошлого могло остаться от прежнего выбора; включённое
        // «как можно скорее» его не касается.
        val form = pickup().copy(asap = true, scheduledAt = now.minusHours(2))

        assertTrue(validate(form).isEmpty())
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
        val form = CheckoutForm(
            method = DeliveryMethod.Delivery,
            address = "",
            asap = false,
            scheduledAt = null,
        )

        val errors = validate(form, walletBalanceSum = 0)

        assertEquals(3, errors.size)
        assertFalse(
            CheckoutValidator.canSubmit(form, totals, false, 0, now),
        )
    }

    @Test
    fun `slots start after the minimum lead and step by half an hour`() {
        val slots = DeliverySlots.next(LocalDateTime.of(2026, 8, 26, 12, 5), count = 3)

        assertEquals(
            listOf(
                LocalDateTime.of(2026, 8, 26, 13, 0),
                LocalDateTime.of(2026, 8, 26, 13, 30),
                LocalDateTime.of(2026, 8, 26, 14, 0),
            ),
            slots,
        )
    }

    @Test
    fun `the first slot always passes validation`() {
        // Округление вниз дало бы слот, который валидация тут же и отвергнет.
        val first = DeliverySlots.next(now, count = 1).single()

        assertTrue(validate(pickup().copy(asap = false, scheduledAt = first)).isEmpty())
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
        now = now,
    )
}
