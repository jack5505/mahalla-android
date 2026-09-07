package uz.mahalla.feature.subscription.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правила оформленной подписки (issue #103): что с ней можно сделать и как
 * разбирается её состояние.
 */
class SubscriptionTest {

    @Test
    fun `the status of the server is parsed with its spellings`() {
        assertEquals(SubscriptionStatus.Active, SubscriptionStatus.fromServer("ACTIVE"))
        assertEquals(SubscriptionStatus.Active, SubscriptionStatus.fromServer(" trial "))
        assertEquals(SubscriptionStatus.Expired, SubscriptionStatus.fromServer("EXPIRED"))
        assertEquals(SubscriptionStatus.Cancelled, SubscriptionStatus.fromServer("CANCELED"))
        assertEquals(SubscriptionStatus.Cancelled, SubscriptionStatus.fromServer("CANCELLED"))
    }

    @Test
    fun `an unknown status is not turned into an expired one`() {
        // Новый статус бэкенда не должен превращаться в «истекла» у всех.
        assertEquals(SubscriptionStatus.Unknown, SubscriptionStatus.fromServer("PAUSED"))
        assertEquals(SubscriptionStatus.Unknown, SubscriptionStatus.fromServer(null))
    }

    @Test
    fun `an active subscription can be cancelled and its auto-renew toggled`() {
        val subscription = subscription(SubscriptionStatus.Active)

        assertTrue(subscription.canCancel)
        assertTrue(subscription.canToggleAutoRenew)
    }

    @Test
    fun `there is nothing to cancel in a cancelled or expired subscription`() {
        assertFalse(subscription(SubscriptionStatus.Cancelled).canCancel)
        assertFalse(subscription(SubscriptionStatus.Expired).canCancel)
        assertFalse(subscription(SubscriptionStatus.Cancelled).canToggleAutoRenew)
    }

    @Test
    fun `an unknown status does not lock a person inside a paid subscription`() {
        // «Неизвестно, чем кончилось» — не «кончилось»: последнее слово всё
        // равно за сервером (то же правило, что у талона очереди, issue #96).
        assertTrue(subscription(SubscriptionStatus.Unknown).canCancel)
    }

    private fun subscription(status: SubscriptionStatus) = Subscription(
        planCode = "PRO",
        status = status,
        isActive = status == SubscriptionStatus.Active,
    )
}
