package uz.mahalla.feature.subscription.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uz.mahalla.core.format.DateTimeFormatters
import java.time.Instant

/**
 * Даты продления (эпик #13, задачи 9.2 и 9.3): когда спишут в следующий раз и
 * до какого числа доведёт продление.
 */
class SubscriptionRenewalTest {

    @Test
    fun `the next charge is the end of the paid term`() {
        // Своего расчёта здесь нет: это `expiresAt` сервера — просто взятая
        // тогда, когда списание вообще будет.
        val subscription = subscription(autoRenew = true, expiresAt = EXPIRES)

        assertEquals(EXPIRES, SubscriptionRenewal.nextChargeAt(subscription))
    }

    @Test
    fun `there is no next charge without auto-renew`() {
        val subscription = subscription(autoRenew = false, expiresAt = EXPIRES)

        assertNull(SubscriptionRenewal.nextChargeAt(subscription))
    }

    @Test
    fun `an expired subscription has no next charge`() {
        // `expiresAt` у неё в прошлом, и назвать эту дату «следующим
        // списанием» значило бы соврать.
        val subscription = subscription(
            autoRenew = true,
            expiresAt = EXPIRES,
            status = SubscriptionStatus.Expired,
            isActive = false,
        )

        assertNull(SubscriptionRenewal.nextChargeAt(subscription))
    }

    @Test
    fun `a cancelled subscription has no next charge either`() {
        val subscription = subscription(
            autoRenew = true,
            expiresAt = EXPIRES,
            status = SubscriptionStatus.Cancelled,
        )

        assertNull(SubscriptionRenewal.nextChargeAt(subscription))
    }

    @Test
    fun `in the grace period the date of the next charge is not promised`() {
        // Срок списания в грейсе решает бэкенд (он же его и повторяет), и
        // приложение его не знает.
        val subscription = subscription(
            autoRenew = true,
            expiresAt = EXPIRES,
            inGracePeriod = true,
        )

        assertNull(SubscriptionRenewal.nextChargeAt(subscription))
    }

    @Test
    fun `renewal is counted from the end of the term while it has not come`() {
        // Грейс-период: доступ ещё есть, и сроком бэкенд может считать его
        // конец. Оплаченное не теряется — месяц прибавляется к `expiresAt`, а
        // не к «сегодня». Это единственное состояние с кнопкой продления, у
        // которого срок бывает в будущем.
        val subscription = subscription(
            expiresAt = Instant.parse("2026-10-04T09:00:00Z"),
            inGracePeriod = true,
        )

        assertEquals(
            Instant.parse("2026-11-04T09:00:00Z"),
            SubscriptionRenewal.renewedUntil(subscription, BillingPeriod.Monthly, NOW),
        )
    }

    @Test
    fun `renewal of a cancelled subscription is counted from now, not from its term`() {
        // Доступ по ней ещё есть, но прибавит ли сервер срок к остатку — из
        // контракта не следует. Обещать «до» на месяц позже настоящего нельзя:
        // то же правило, что у выгоды годовой оплаты, — округляем вниз.
        val subscription = subscription(
            status = SubscriptionStatus.Cancelled,
            expiresAt = Instant.parse("2026-09-28T09:00:00Z"),
        )

        assertEquals(
            Instant.parse("2026-10-08T07:00:00Z"),
            SubscriptionRenewal.renewedUntil(subscription, BillingPeriod.Monthly, NOW),
        )
    }

    @Test
    fun `renewal of an expired subscription is counted from now`() {
        // Прибавлять месяц к прошлогодней дате нечего: срок пойдёт с момента
        // оплаты.
        val subscription = subscription(
            expiresAt = Instant.parse("2026-01-04T09:00:00Z"),
            status = SubscriptionStatus.Expired,
            isActive = false,
        )

        assertEquals(
            Instant.parse("2026-10-08T07:00:00Z"),
            SubscriptionRenewal.renewedUntil(subscription, BillingPeriod.Monthly, NOW),
        )
    }

    @Test
    fun `a subscription without a term is renewed from now`() {
        assertEquals(
            Instant.parse("2027-09-08T07:00:00Z"),
            SubscriptionRenewal.renewedUntil(subscription(), BillingPeriod.Yearly, NOW),
        )
    }

    @Test
    fun `a year is added as a calendar year, and a leap one too`() {
        val subscription = subscription(expiresAt = Instant.parse("2028-02-29T09:00:00Z"))

        assertEquals(
            // 29 февраля 2028 + год = 28 февраля 2029: такого числа в 2029 нет.
            Instant.parse("2029-02-28T09:00:00Z"),
            SubscriptionRenewal.renewedUntil(subscription, BillingPeriod.Yearly, NOW),
        )
    }

    @Test
    fun `the end of the month does not slip into the next one`() {
        // 31 января + месяц = 28 февраля, а не 3 марта: `plus(30 дней)` здесь
        // соврал бы.
        val subscription = subscription(expiresAt = Instant.parse("2027-01-31T09:00:00Z"))

        assertEquals(
            Instant.parse("2027-02-28T09:00:00Z"),
            SubscriptionRenewal.renewedUntil(subscription, BillingPeriod.Monthly, NOW),
        )
    }

    @Test
    fun `the term is counted in the zone of the app`() {
        // 30 января, 23:00 в Ташкенте (18:00 UTC) — месяц отсчитывается от
        // ташкентской даты, поэтому получается 28 февраля, а не 1 марта.
        val subscription = subscription(expiresAt = Instant.parse("2027-01-30T18:00:00Z"))

        val renewedUntil = SubscriptionRenewal.renewedUntil(
            subscription,
            BillingPeriod.Monthly,
            NOW,
        )

        assertEquals(
            "28.02.2027",
            DateTimeFormatters.date(renewedUntil),
        )
    }

    @Test
    fun `the period is taken from the subscription, and the selected one is only a fallback`() {
        // У годовой подписки «помесячно» — это и не та цена, и не та дата.
        assertEquals(
            BillingPeriod.Yearly,
            SubscriptionRenewal.periodOf(
                subscription(period = BillingPeriod.Yearly),
                BillingPeriod.Monthly,
            ),
        )
        assertEquals(
            BillingPeriod.Yearly,
            SubscriptionRenewal.periodOf(subscription(period = null), BillingPeriod.Yearly),
        )
    }

    private fun subscription(
        status: SubscriptionStatus = SubscriptionStatus.Active,
        period: BillingPeriod? = BillingPeriod.Monthly,
        autoRenew: Boolean = false,
        expiresAt: Instant? = null,
        isActive: Boolean = true,
        inGracePeriod: Boolean = false,
    ) = Subscription(
        planCode = "PRO",
        status = status,
        billingPeriod = period,
        autoRenew = autoRenew,
        expiresAt = expiresAt,
        isActive = isActive,
        inGracePeriod = inGracePeriod,
    )

    private companion object {
        /** «Сейчас» тестов: 8 сентября 2026, полдень в Ташкенте. */
        val NOW: Instant = Instant.parse("2026-09-08T07:00:00Z")
        val EXPIRES: Instant = Instant.parse("2026-10-04T09:00:00Z")
    }
}
