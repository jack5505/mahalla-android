package uz.mahalla.feature.role.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правило «человек оказывает услуги» (issue #244): анкета продавца **или**
 * права на сервере, ни одно не отменяет другое.
 */
class ProvidesServicesTest {

    @Test
    fun `provider form is enough on its own`() {
        assertTrue(providesServices(UserRole.Provider, ServerRole.Unknown))
    }

    @Test
    fun `server role is enough without any form`() {
        assertTrue(providesServices(null, ServerRole.FoodOwner))
    }

    @Test
    fun `customer form and a provider server role both say yes`() {
        // Владелец кинотеатра, заполнивший анкету покупателя: одно другому
        // не мешает, и права важнее анкеты.
        assertTrue(providesServices(UserRole.Customer, ServerRole.CinemaOwner))
    }

    @Test
    fun `plain user with no form says no`() {
        assertFalse(providesServices(null, ServerRole.User))
    }

    @Test
    fun `admin does not get provider treatment`() {
        assertFalse(providesServices(null, ServerRole.Admin))
    }
}
