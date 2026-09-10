package uz.mahalla.feature.role.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правило «оказывает ли человек услуги» (issue #244): по нему показываются
 * «Мои заведения» и выбирается аудитория тарифов подписки. Раньше первое
 * спрашивало оба источника, второе — только анкету, и человек видел
 * расхождение.
 */
class ProvidesServicesTest {

    @Test
    fun `the provider form alone is enough`() {
        // Анкету заполнили, входа с правами не было: заявка — тоже ответ.
        assertTrue(providesServices(UserRole.Provider, ServerRole.Unknown))
    }

    @Test
    fun `the server role alone is enough`() {
        // Настоящий владелец кафе, который анкету не заполнял.
        assertTrue(providesServices(formRole = null, serverRole = ServerRole.FoodOwner))
    }

    @Test
    fun `the customer form does not hide the rights of the server`() {
        // Владелец кинотеатра, выбравший анкету покупателя: он и правда
        // покупает еду, но бизнес прятать из-за этого нельзя.
        assertTrue(providesServices(UserRole.Customer, ServerRole.CinemaOwner))
    }

    @Test
    fun `a plain user provides nothing`() {
        assertFalse(providesServices(UserRole.Customer, ServerRole.User))
        assertFalse(providesServices(formRole = null, serverRole = ServerRole.Unknown))
    }

    @Test
    fun `an admin is not a provider`() {
        // Админка живёт отдельно, приложение администратору бизнеса не даёт.
        assertFalse(providesServices(formRole = null, serverRole = ServerRole.Admin))
    }
}
