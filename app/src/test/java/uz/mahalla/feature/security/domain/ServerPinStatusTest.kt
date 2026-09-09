package uz.mahalla.feature.security.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор ответов `pin/status` и `auth/session/check` (issue #102).
 *
 * Все поля обеих схем необязательны, поэтому главное здесь — что именно
 * значит молчание сервера. Ответы на этот вопрос у двух схем разные, и оба
 * выбраны так, чтобы неприехавшее поле не запирало человека.
 */
class ServerPinStatusTest {

    @Test
    fun `silent flags read as off`() {
        val status = ServerPinStatus.of(
            pinSet = null,
            biometricEnabled = null,
            lockedSecondsRemaining = null,
        )

        assertFalse(status.pinSet)
        assertFalse(status.biometricEnabled)
        assertFalse(status.locked)
        assertEquals(0L, status.lockedSecondsRemaining)
    }

    @Test
    fun `lock is reported only while seconds remain`() {
        assertTrue(ServerPinStatus.of(true, false, 42).locked)
        assertFalse(ServerPinStatus.of(true, false, 0).locked)
    }

    @Test
    fun `negative remainder is not a lock that never ends`() {
        // «Заблокировано на −5 секунд» не значит ничего, а обратный отсчёт по
        // такому числу не закончился бы никогда.
        val status = ServerPinStatus.of(true, false, -5)

        assertEquals(0L, status.lockedSecondsRemaining)
        assertFalse(status.locked)
    }

    @Test
    fun `silent session validity means the session is alive`() {
        // Осторожность здесь работает в обратную сторону: `false` по умолчанию
        // выкидывал бы человека из аккаунта из-за неприехавшего поля.
        val check = SessionCheck.of(sessionValid = null, pinRequired = null, reason = null)

        assertTrue(check.valid)
        assertFalse(check.pinRequired)
    }

    @Test
    fun `dead session is reported with its reason`() {
        val check = SessionCheck.of(
            sessionValid = false,
            pinRequired = true,
            reason = "SESSION_REVOKED",
        )

        assertFalse(check.valid)
        assertTrue(check.pinRequired)
        assertEquals("SESSION_REVOKED", check.reason)
    }

    @Test
    fun `blank reason is the same as no reason`() {
        assertEquals(null, SessionCheck.of(true, false, "   ").reason)
    }

    @Test
    fun `change rules demand six digits`() {
        assertTrue(ChangePinRules.isWellFormed("123456"))
        assertFalse(ChangePinRules.isWellFormed("12345"))
        assertFalse(ChangePinRules.isWellFormed("1234567"))
        assertFalse(ChangePinRules.isWellFormed(""))
        // Не-цифры бэкенд отвергнет по `^[0-9]{6}$` — отсекаем до сети.
        assertFalse(ChangePinRules.isWellFormed("12345a"))
        assertFalse(ChangePinRules.isWellFormed("１２３４５６"))
    }

    @Test
    fun `new pin equal to the current one is not a change`() {
        assertTrue(ChangePinRules.isSameAsCurrent("111111", "111111"))
        assertFalse(ChangePinRules.isSameAsCurrent("111111", "111112"))
    }
}
