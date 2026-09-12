package uz.mahalla.feature.profile.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Статус проверки и статус аккаунта (issue #237). Значения — из живой схемы
 * стенда (`MeResponse`, снято 2026-09-10).
 */
class AccountStateTest {

    @Test
    fun `verification statuses of the schema are recognised`() {
        assertEquals(VerificationStatus.Unverified, VerificationStatus.fromServer("UNVERIFIED"))
        assertEquals(VerificationStatus.SmsVerified, VerificationStatus.fromServer("SMS_VERIFIED"))
        assertEquals(VerificationStatus.FullVerified, VerificationStatus.fromServer("FULL_VERIFIED"))
    }

    @Test
    fun `unknown verification status does not break the profile`() {
        assertEquals(VerificationStatus.Unknown, VerificationStatus.fromServer("PASSPORT_VERIFIED"))
        assertEquals(VerificationStatus.Unknown, VerificationStatus.fromServer(null))
        assertEquals(VerificationStatus.Unknown, VerificationStatus.fromServer(""))
    }

    @Test
    fun `account statuses of the schema are recognised`() {
        assertEquals(AccountStatus.Active, AccountStatus.fromServer("ACTIVE"))
        assertEquals(AccountStatus.TempBlocked, AccountStatus.fromServer("TEMP_BLOCKED"))
        assertEquals(AccountStatus.PermBlocked, AccountStatus.fromServer("PERM_BLOCKED"))
        assertEquals(AccountStatus.Suspended, AccountStatus.fromServer("SUSPENDED"))
        assertEquals(AccountStatus.Deleted, AccountStatus.fromServer("DELETED"))
        assertEquals(AccountStatus.Unknown, AccountStatus.fromServer("SHADOW_BANNED"))
        // Регистр и пробелы бэкенда не должны решать, заперт аккаунт или нет.
        assertEquals(AccountStatus.PermBlocked, AccountStatus.fromServer(" perm_blocked "))
    }

    @Test
    fun `no status of the schema is lost`() {
        // Пять состояний схемы плюс Unknown: разошлась схема — тест упал, а
        // не показал человеку «аккаунт в порядке» на заблокированном.
        assertEquals(6, AccountStatus.entries.size)
        assertEquals(4, VerificationStatus.entries.size)
    }
}
