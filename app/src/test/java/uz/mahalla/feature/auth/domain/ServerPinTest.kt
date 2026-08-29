package uz.mahalla.feature.auth.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Разбор `nextStep` из ответа `auth/verify-otp` (issue #51): именно он решает,
 * каким запросом добирать токены.
 */
class ServerPinTest {

    @Test
    fun `known steps are taken literally`() {
        assertEquals(ServerPinStep.Setup, ServerPin.stepOf("SETUP_PIN", pinConfigured = true))
        assertEquals(ServerPinStep.Enter, ServerPin.stepOf("ENTER_PIN", pinConfigured = false))
    }

    @Test
    fun `case and spaces do not change the step`() {
        assertEquals(ServerPinStep.Setup, ServerPin.stepOf(" setup_pin ", pinConfigured = true))
    }

    @Test
    fun `an unknown step falls back to what the profile says about the pin`() {
        // Токенов в ответе всё равно нет, значит шаг с PIN предстоит: остаётся
        // выбрать, придумывать код или вводить.
        assertEquals(ServerPinStep.Enter, ServerPin.stepOf(null, pinConfigured = true))
        assertEquals(ServerPinStep.Setup, ServerPin.stepOf(null, pinConfigured = false))
        assertEquals(ServerPinStep.Enter, ServerPin.stepOf("NONE", pinConfigured = true))
    }

    @Test
    fun `the length matches what the backend accepts`() {
        // `^[0-9]{6}$` в схемах SetupPinRequest и PinLoginRequest.
        assertEquals(6, ServerPin.LENGTH)
    }
}
