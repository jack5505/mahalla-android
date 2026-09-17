package uz.mahalla.feature.subscription.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Состояния подписки для экрана (эпик #13, задача 9.3): активна, истекает,
 * истекла, отменена — и то, у какой из них есть смысл в кнопке продления.
 */
class SubscriptionStageTest {

    @Test
    fun `an active subscription with a long term left is active`() {
        val subscription = subscription(daysRemaining = 21)

        assertEquals(SubscriptionStage.Active, subscription.stage)
        assertFalse(subscription.canRenew)
    }

    @Test
    fun `the last days of the term are shown as expiring, not as active`() {
        // Отдельного статуса под это у бэкенда нет: состояние выводится из
        // остатка дней, который он же и посчитал.
        assertEquals(
            SubscriptionStage.ExpiringSoon,
            subscription(daysRemaining = SubscriptionStage.EXPIRING_SOON_DAYS).stage,
        )
        assertEquals(SubscriptionStage.ExpiringSoon, subscription(daysRemaining = 0).stage)
        assertEquals(
            SubscriptionStage.Active,
            subscription(daysRemaining = SubscriptionStage.EXPIRING_SOON_DAYS + 1).stage,
        )
    }

    @Test
    fun `the grace period is expiring too - and it is worth renewing`() {
        // Оплаченный срок кончился, доступ ещё есть: ровно тот случай, когда
        // продление спасает от потери доступа.
        val subscription = subscription(daysRemaining = 30, inGracePeriod = true)

        assertEquals(SubscriptionStage.ExpiringSoon, subscription.stage)
        assertTrue(subscription.canRenew)
    }

    @Test
    fun `an expired subscription is expired and can be renewed`() {
        val byStatus = subscription(status = SubscriptionStatus.Expired, isActive = false)
        assertEquals(SubscriptionStage.Expired, byStatus.stage)
        assertTrue(byStatus.canRenew)

        // «EXPIRED» от сервера сильнее прочих полей: даже с остатком дней это
        // истёкшая подписка.
        assertEquals(
            SubscriptionStage.Expired,
            subscription(status = SubscriptionStatus.Expired, daysRemaining = 10).stage,
        )
    }

    @Test
    fun `a cancelled subscription stays cancelled even while it still works`() {
        // Доступ до конца оплаченного срока бэкенд оставляет, но продлевать её
        // не будет — «активна» здесь было бы ложью про деньги.
        val subscription = subscription(
            status = SubscriptionStatus.Cancelled,
            daysRemaining = 20,
            isActive = true,
        )

        assertEquals(SubscriptionStage.Cancelled, subscription.stage)
        // Вернуть её можно только оформлением заново — значит кнопка нужна.
        assertTrue(subscription.canRenew)
    }

    @Test
    fun `a server that says it does not work is believed even with an unknown status`() {
        val subscription = subscription(status = SubscriptionStatus.Unknown, isActive = false)

        assertEquals(SubscriptionStage.Expired, subscription.stage)
        assertTrue(subscription.canRenew)
    }

    @Test
    fun `an unknown status of a working subscription is not turned into expired`() {
        // Новое значение `status` не должно отнимать доступ у всех подряд.
        val subscription = subscription(status = SubscriptionStatus.Unknown, isActive = true)

        assertEquals(SubscriptionStage.Unknown, subscription.stage)
        assertFalse(subscription.canRenew)
    }

    @Test
    fun `without the days remaining the subscription is not called expiring`() {
        // Своего расчёта от `expiresAt` здесь нет намеренно: остаток дней
        // считает сервер, у него есть грейс-период.
        assertEquals(SubscriptionStage.Active, subscription(daysRemaining = null).stage)
    }

    private fun subscription(
        status: SubscriptionStatus = SubscriptionStatus.Active,
        daysRemaining: Long? = null,
        isActive: Boolean = true,
        inGracePeriod: Boolean = false,
    ) = Subscription(
        planCode = "PRO",
        status = status,
        daysRemaining = daysRemaining,
        isActive = isActive,
        inGracePeriod = inGracePeriod,
    )
}
