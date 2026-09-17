package uz.mahalla.feature.profile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.data.prefs.AppSettings
import uz.mahalla.data.prefs.UserProfile
import uz.mahalla.feature.profile.domain.AccountStatus
import uz.mahalla.feature.profile.domain.VerificationStatus
import uz.mahalla.feature.role.domain.ServerRole
import uz.mahalla.feature.role.domain.UserRole

/**
 * Два значения слова «роль» на одном экране (issue #237): анкета лежит в
 * настройках и её выбирает человек, права приезжают с сервера. Правило «кому
 * показывать «Мои заведения»» проверяется здесь, а не в Compose: эмулятора в
 * CI нет.
 */
class ProfileStateTest {

    @Test
    fun `form role and server role are read from different places`() {
        val state = ProfileState(
            settings = AppSettings(roleId = UserRole.Customer.storedValue),
            profile = UserProfile(serverRole = "CINEMA_OWNER"),
        )

        // Одно другому не противоречит: владелец кинотеатра сам покупает еду.
        assertEquals(UserRole.Customer, state.formRole)
        assertEquals(ServerRole.CinemaOwner, state.serverRole)
    }

    @Test
    fun `provider form opens my places`() {
        val state = ProfileState(settings = AppSettings(roleId = UserRole.Provider.storedValue))

        assertTrue(state.showMyPlaces)
    }

    @Test
    fun `server role opens my places without any form`() {
        // Настоящий владелец заведения, который анкету не заполнял: до
        // issue #237 своего заведения в приложении не было видно вовсе.
        val state = ProfileState(profile = UserProfile(serverRole = "FOOD_OWNER"))

        assertTrue(state.showMyPlaces)
    }

    @Test
    fun `plain user sees no my places row`() {
        val state = ProfileState(
            settings = AppSettings(roleId = UserRole.Customer.storedValue),
            profile = UserProfile(serverRole = "USER"),
        )

        assertFalse(state.showMyPlaces)
    }

    @Test
    fun `empty state hides my places`() {
        // Ни анкеты, ни ответа сервера: строка со списком, который заведомо
        // пуст, только сбивает.
        assertFalse(ProfileState().showMyPlaces)
        assertEquals(ServerRole.Unknown, ProfileState().serverRole)
    }

    @Test
    fun `statuses come from the stored profile`() {
        val state = ProfileState(
            profile = UserProfile(
                verificationStatus = "UNVERIFIED",
                accountStatus = "PERM_BLOCKED",
            ),
        )

        assertEquals(VerificationStatus.Unverified, state.verification)
        assertEquals(AccountStatus.PermBlocked, state.account)
    }
}
