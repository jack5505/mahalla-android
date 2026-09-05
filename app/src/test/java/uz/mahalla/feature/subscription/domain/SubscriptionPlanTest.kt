package uz.mahalla.feature.subscription.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.feature.wallet.domain.WalletAmounts

/**
 * Правила тарифа (issue #103): название по языку, выгода годовой оплаты,
 * пробный период и единица цен.
 */
class SubscriptionPlanTest {

    @Test
    fun `the uzbek name wins in the uzbek interface`() {
        val plan = plan(name = "Профи", nameUz = "Pro")

        assertEquals("Pro", plan.displayName(uzbek = true))
        assertEquals("Профи", plan.displayName(uzbek = false))
    }

    @Test
    fun `an empty name falls back to the other language, then to the code`() {
        // Узбекского названия у нового тарифа может не быть — пустая строка
        // вместо имени хуже названия на другом языке.
        assertEquals("Профи", plan(name = "Профи", nameUz = "  ").displayName(uzbek = true))
        assertEquals("Pro", plan(name = null, nameUz = "Pro").displayName(uzbek = false))
        assertEquals("PRO", plan(name = null, nameUz = null).displayName(uzbek = true))
    }

    @Test
    fun `the server discount is preferred over our own arithmetic`() {
        // Считает сервер, а показывать надо то, что он же и спишет.
        val plan = plan(monthlySum = 50_000, yearlySum = 500_000, discount = 25)

        assertEquals(25, plan.savingsPercent)
    }

    @Test
    fun `the discount is computed from prices when the server keeps silent`() {
        // 12 × 50 000 = 600 000 против 480 000 — ровно 20 %.
        val plan = plan(monthlySum = 50_000, yearlySum = 480_000, discount = 0)

        assertEquals(20, plan.savingsPercent)
    }

    @Test
    fun `a computed discount is rounded down`() {
        // 600 000 против 500 000 — 16,66 %: обещать 17 нельзя.
        assertEquals(16, plan(monthlySum = 50_000, yearlySum = 500_000, discount = 0).savingsPercent)
    }

    @Test
    fun `there is no discount when the year costs the same or more`() {
        assertEquals(0, plan(monthlySum = 50_000, yearlySum = 600_000, discount = 0).savingsPercent)
        assertEquals(0, plan(monthlySum = 50_000, yearlySum = 700_000, discount = 0).savingsPercent)
        assertEquals(0, plan(monthlySum = 0, yearlySum = 480_000, discount = 0).savingsPercent)
    }

    @Test
    fun `a plan with zero prices is not paid, whatever the free flag says`() {
        assertFalse(plan(monthlySum = 0, yearlySum = 0).isPaid)
        assertFalse(plan(monthlySum = 50_000, isFree = true).isPaid)
        assertTrue(plan(monthlySum = 50_000).isPaid)
    }

    @Test
    fun `a trial is offered only on a paid plan`() {
        // «Попробовать бесплатный бесплатно» — предложение без смысла, и
        // сервер ответил бы на него отказом.
        assertFalse(plan(monthlySum = 0, yearlySum = 0, trialDays = 7).hasTrial)
        assertFalse(plan(monthlySum = 50_000, trialDays = 0).hasTrial)
        assertTrue(plan(monthlySum = 50_000, trialDays = 7).hasTrial)
    }

    @Test
    fun `the price follows the selected period`() {
        val plan = plan(monthlySum = 50_000, yearlySum = 480_000)

        assertEquals(50_000L, plan.priceSum(BillingPeriod.Monthly))
        assertEquals(480_000L, plan.priceSum(BillingPeriod.Yearly))
    }

    @Test
    fun `plan codes are compared ignoring case and spaces`() {
        val plan = plan()

        assertTrue(plan.isSameCode("pro"))
        assertTrue(plan.isSameCode(" PRO "))
        assertFalse(plan.isSameCode("PREMIUM"))
        assertFalse(plan.isSameCode(null))
    }

    @Test
    fun `the billing period of the server is parsed, and an unknown one is not guessed`() {
        assertEquals(BillingPeriod.Monthly, BillingPeriod.fromServer("monthly"))
        assertEquals(BillingPeriod.Yearly, BillingPeriod.fromServer(" YEARLY "))
        // «Оплачено помесячно» там, где на самом деле год, — это неверная дата
        // следующего списания на экране.
        assertEquals(null, BillingPeriod.fromServer("WEEKLY"))
        assertEquals(null, BillingPeriod.fromServer(null))
    }

    @Test
    fun `an unknown audience does not hide the plan`() {
        assertEquals(PlanAudience.User, PlanAudience.fromServer("USER"))
        assertEquals(PlanAudience.Business, PlanAudience.fromServer("business"))
        assertEquals(PlanAudience.Unknown, PlanAudience.fromServer("PARTNER"))
        // Молчание сервера — обычный пользователь: именно его берёт по
        // умолчанию и сам бэкенд.
        assertEquals(PlanAudience.User, PlanAudience.fromServer(null))
    }

    @Test
    fun `the unit of prices is derived from the monthly pair`() {
        assertEquals(
            WalletAmounts.TIYIN_IN_SOM,
            SubscriptionAmounts.scaleOf(
                monthly = 4_900_000,
                monthlySom = 49_000.0,
                yearly = null,
                yearlySom = null,
            ),
        )
        assertEquals(
            1L,
            SubscriptionAmounts.scaleOf(
                monthly = 49_000,
                monthlySom = 49_000.0,
                yearly = null,
                yearlySom = null,
            ),
        )
    }

    @Test
    fun `the yearly pair is used when there is no monthly price`() {
        // У тарифа вполне может быть только годовая цена — тогда месячная пара
        // не доказывает ничего.
        assertEquals(
            1L,
            SubscriptionAmounts.scaleOf(
                monthly = 0,
                monthlySom = 0.0,
                yearly = 480_000,
                yearlySom = 480_000.0,
            ),
        )
    }

    @Test
    fun `without any pair the minor unit is assumed`() {
        // Отдельное поле `*Som` существует ровно потому, что целое поле хранит
        // что-то другое (то же решение, что в кошельке, issue #62).
        assertEquals(
            WalletAmounts.TIYIN_IN_SOM,
            SubscriptionAmounts.scaleOf(null, null, null, null),
        )
    }

    private fun plan(
        name: String? = "Pro",
        nameUz: String? = "Pro",
        monthlySum: Long = 50_000,
        yearlySum: Long = 480_000,
        discount: Int = 0,
        trialDays: Int = 0,
        isFree: Boolean = false,
    ) = SubscriptionPlan(
        code = "PRO",
        name = name,
        nameUz = nameUz,
        monthlySum = monthlySum,
        yearlySum = yearlySum,
        yearlyDiscountPercent = discount,
        trialDays = trialDays,
        isFree = isFree,
    )
}
