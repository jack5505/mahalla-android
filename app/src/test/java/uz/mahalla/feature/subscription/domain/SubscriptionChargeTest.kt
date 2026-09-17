package uz.mahalla.feature.subscription.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Списания за подписку (эпик #13, задача 9.3): что относится к подписке и как
 * разбираются исход платежа и платёжная система.
 */
class SubscriptionChargeTest {

    @Test
    fun `a subscription payment is recognized by its purpose`() {
        assertTrue(SubscriptionCharge.isSubscriptionPurpose("SUBSCRIPTION"))
        assertTrue(SubscriptionCharge.isSubscriptionPurpose(" subscription "))
        // Своей ручки у истории подписки нет, а значения `purpose` бэкенд в
        // схеме не фиксирует: продление рядом с оформлением пропасть не должно.
        assertTrue(SubscriptionCharge.isSubscriptionPurpose("SUBSCRIPTION_RENEWAL"))
    }

    @Test
    fun `other payments do not get into the history of the subscription`() {
        assertFalse(SubscriptionCharge.isSubscriptionPurpose("WALLET_TOP_UP"))
        assertFalse(SubscriptionCharge.isSubscriptionPurpose("ORDER"))
        assertFalse(SubscriptionCharge.isSubscriptionPurpose(""))
        assertFalse(SubscriptionCharge.isSubscriptionPurpose(null))
    }

    @Test
    fun `the outcome of the payment is parsed with the spellings of the server`() {
        assertEquals(ChargeStatus.Paid, ChargeStatus.fromServer("PAID"))
        assertEquals(ChargeStatus.Pending, ChargeStatus.fromServer(" pending "))
        assertEquals(ChargeStatus.Failed, ChargeStatus.fromServer("FAILED"))
        assertEquals(ChargeStatus.Cancelled, ChargeStatus.fromServer("CANCELED"))
        assertEquals(ChargeStatus.Refunded, ChargeStatus.fromServer("REFUNDED"))
    }

    @Test
    fun `the spellings beyond the schema are accepted too`() {
        // Схема фиксирует пять значений, но `status` в ней описан как `string`
        // у половины ручек — распространённые синонимы принимаются заранее.
        assertEquals(ChargeStatus.Pending, ChargeStatus.fromServer("PROCESSING"))
        assertEquals(ChargeStatus.Pending, ChargeStatus.fromServer("CREATED"))
        assertEquals(ChargeStatus.Paid, ChargeStatus.fromServer("COMPLETED"))
        assertEquals(ChargeStatus.Paid, ChargeStatus.fromServer("SUCCESS"))
        assertEquals(ChargeStatus.Paid, ChargeStatus.fromServer("SUCCEEDED"))
        assertEquals(ChargeStatus.Failed, ChargeStatus.fromServer("ERROR"))
        assertEquals(ChargeStatus.Failed, ChargeStatus.fromServer("REJECTED"))
        assertEquals(ChargeStatus.Refunded, ChargeStatus.fromServer("REVERSED"))
    }

    @Test
    fun `an unknown outcome is not turned into a failed payment`() {
        assertEquals(ChargeStatus.Unknown, ChargeStatus.fromServer("ON_HOLD"))
        assertEquals(ChargeStatus.Unknown, ChargeStatus.fromServer(null))
    }

    @Test
    fun `the payment system is parsed, and an unknown one stays unnamed`() {
        assertEquals(ChargeProvider.Payme, ChargeProvider.fromServer("PAYME"))
        assertEquals(ChargeProvider.Click, ChargeProvider.fromServer(" click "))
        assertEquals(ChargeProvider.Uzum, ChargeProvider.fromServer("UZUM"))
        assertEquals(ChargeProvider.Cash, ChargeProvider.fromServer("CASH"))
        assertEquals(ChargeProvider.Unknown, ChargeProvider.fromServer("UZCARD"))
        // Пустое значение не должно совпасть с `apiValue` самого `Unknown`
        // случайно — оно и так [ChargeProvider.Unknown].
        assertEquals(ChargeProvider.Unknown, ChargeProvider.fromServer(""))
        assertEquals(ChargeProvider.Unknown, ChargeProvider.fromServer(null))
    }
}
