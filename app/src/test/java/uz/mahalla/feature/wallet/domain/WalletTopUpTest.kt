package uz.mahalla.feature.wallet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пополнение кошелька (issue #93): правила черновика, единицы суммы и проверка
 * ссылки на форму оплаты.
 *
 * Главное здесь — единицы. Бэкенд принимает `amount` в своих единицах с
 * минимумом `100000`, а человек вводит сумы; делитель выводится из выдачи
 * баланса ([WalletAmounts]), и ошибка в сто раз означала бы списание в сто раз
 * больше задуманного.
 */
class WalletTopUpTest {

    @Test
    fun `minimum is named in sums by the scale of the response`() {
        // Тийины: серверные 100 000 — это 1 000 сум.
        assertEquals(1_000L, WalletTopUp.minAmountSum(WalletAmounts.TIYIN_IN_SOM))
        // Сумы: минимум ровно тот, что в схеме.
        assertEquals(100_000L, WalletTopUp.minAmountSum(1L))
    }

    /**
     * Округление вверх: при округлении вниз подпись обещала бы сумму, которую
     * сервер отвергнет как слишком маленькую.
     */
    @Test
    fun `minimum is rounded up`() {
        assertEquals(33_334L, WalletTopUp.minAmountSum(3L))
        assertTrue(WalletTopUp.toMinor(WalletTopUp.minAmountSum(3L), 3L) >= WalletTopUp.MIN_AMOUNT_MINOR)
    }

    @Test
    fun `amount is sent in the units of the backend`() {
        assertEquals(25_000_000L, WalletTopUp.toMinor(250_000L, WalletAmounts.TIYIN_IN_SOM))
        assertEquals(250_000L, WalletTopUp.toMinor(250_000L, 1L))
    }

    /**
     * Суммы показываются с неразрывными пробелами между разрядами, и
     * вставленное из другого места «100 000» — обычный ввод.
     */
    @Test
    fun `amount is read from digits only`() {
        assertEquals(100_000L, WalletTopUp.parseAmount("100 000"))
        assertEquals(100_000L, WalletTopUp.parseAmount("100 000 so'm"))
        assertEquals(50_000L, WalletTopUp.parseAmount(" 50000 "))
    }

    @Test
    fun `empty, zero and absurd amounts are not amounts`() {
        assertNull(WalletTopUp.parseAmount(""))
        assertNull(WalletTopUp.parseAmount("so'm"))
        assertNull(WalletTopUp.parseAmount("0"))
        // Число, которое не влезло бы даже в Long, не должно ронять разбор.
        assertNull(WalletTopUp.parseAmount("9".repeat(30)))
    }

    @Test
    fun `all reasons are reported at once`() {
        val errors = TopUpValidator.validate(TopUpDraft(), WalletAmounts.TIYIN_IN_SOM)

        assertEquals(setOf(TopUpError.AmountRequired, TopUpError.ProviderRequired), errors)
    }

    @Test
    fun `amount below the server minimum does not pass`() {
        val draft = TopUpDraft(amountText = "999", provider = TopUpProvider.Payme)

        assertEquals(
            setOf(TopUpError.AmountTooSmall),
            TopUpValidator.validate(draft, WalletAmounts.TIYIN_IN_SOM),
        )
        // При другом делителе та же сумма — уже отказ по другому порогу.
        assertEquals(
            setOf(TopUpError.AmountTooSmall),
            TopUpValidator.validate(draft.copy(amountText = "99999"), 1L),
        )
    }

    /** Потолок про лишний ноль: платит человек настоящими деньгами. */
    @Test
    fun `amount above the client cap does not pass`() {
        val draft = TopUpDraft(
            amountText = (WalletTopUp.MAX_AMOUNT_SUM + 1).toString(),
            provider = TopUpProvider.Click,
        )

        assertEquals(
            setOf(TopUpError.AmountTooLarge),
            TopUpValidator.validate(draft, WalletAmounts.TIYIN_IN_SOM),
        )
    }

    @Test
    fun `filled draft passes`() {
        val draft = TopUpDraft(amountText = "250 000", provider = TopUpProvider.Uzum)

        assertTrue(TopUpValidator.validate(draft, WalletAmounts.TIYIN_IN_SOM).isEmpty())
        assertEquals(250_000L, draft.amountSum)
    }

    @Test
    fun `provider is read from the value of the server`() {
        assertEquals(TopUpProvider.Payme, TopUpProvider.fromServer("PAYME"))
        assertEquals(TopUpProvider.Click, TopUpProvider.fromServer(" click "))
        assertEquals(TopUpProvider.Uzum, TopUpProvider.fromServer("Uzum"))
    }

    /** Незнакомый провайдер не подменяется первым в списке. */
    @Test
    fun `unknown provider is not a provider`() {
        assertNull(TopUpProvider.fromServer("CASH"))
        assertNull(TopUpProvider.fromServer(""))
        assertNull(TopUpProvider.fromServer(null))
    }

    @Test
    fun `payment form is opened only over https`() {
        assertEquals(
            "https://checkout.paycom.uz/abc",
            PaymentLink.sanitize("https://checkout.paycom.uz/abc"),
        )
        assertEquals(
            "https://my.click.uz/pay?id=1",
            PaymentLink.sanitize(" https://my.click.uz/pay?id=1 "),
        )
    }

    /**
     * Ссылку присылает сервер, а адрес сервера в debug вводит пользователь:
     * без проверки подменённый бэкенд запускал бы произвольный intent.
     */
    @Test
    fun `foreign schemes and cleartext are rejected`() {
        assertNull(PaymentLink.sanitize("http://checkout.paycom.uz/abc"))
        assertNull(PaymentLink.sanitize("mahalla://place/1"))
        assertNull(PaymentLink.sanitize("intent://evil"))
        assertNull(PaymentLink.sanitize("market://details?id=uz.mahalla"))
        assertNull(PaymentLink.sanitize("javascript:alert(1)"))
    }

    @Test
    fun `link without a host or with spaces inside is rejected`() {
        assertNull(PaymentLink.sanitize("https://"))
        assertNull(PaymentLink.sanitize("https:///pay"))
        assertNull(PaymentLink.sanitize("https://pay me.uz/abc"))
        assertNull(PaymentLink.sanitize(null))
        assertNull(PaymentLink.sanitize("   "))
    }
}
