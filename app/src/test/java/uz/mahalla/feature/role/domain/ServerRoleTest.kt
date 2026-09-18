package uz.mahalla.feature.role.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Роль в правах бэкенда (issue #237). Значения взяты из живой схемы стенда
 * (`MeResponse.role`, `UserInfo.role`, снято 2026-09-10) — четырнадцать штук.
 */
class ServerRoleTest {

    @Test
    fun `all fourteen server roles are recognised`() {
        val fromSchema = mapOf(
            "USER" to ServerRole.User,
            "BARBER" to ServerRole.Barber,
            "BAKER" to ServerRole.Baker,
            "SHOP_OWNER" to ServerRole.ShopOwner,
            "FOOD_OWNER" to ServerRole.FoodOwner,
            "GAMING_OWNER" to ServerRole.GamingOwner,
            "MUSEUM_OWNER" to ServerRole.MuseumOwner,
            "PARK_OWNER" to ServerRole.ParkOwner,
            "MOSQUE_OWNER" to ServerRole.MosqueOwner,
            "PHARMACY_OWNER" to ServerRole.PharmacyOwner,
            "HOSPITAL_OWNER" to ServerRole.HospitalOwner,
            "CINEMA_OWNER" to ServerRole.CinemaOwner,
            "FREELANCER" to ServerRole.Freelancer,
            "ADMIN" to ServerRole.Admin,
        )
        fromSchema.forEach { (raw, expected) ->
            assertEquals(raw, expected, ServerRole.fromServer(raw))
        }
        // Ни одного значения схемы не потеряли: Unknown — только для нового.
        assertEquals(ServerRole.entries.size - 1, fromSchema.size)
    }

    @Test
    fun `unknown role does not break the profile`() {
        // Бэкенд заведёт новую роль — профиль обязан открыться.
        assertEquals(ServerRole.Unknown, ServerRole.fromServer("MAHALLA_CHAIRMAN"))
        assertEquals(ServerRole.Unknown, ServerRole.fromServer(null))
        assertEquals(ServerRole.Unknown, ServerRole.fromServer(""))
        assertEquals(ServerRole.Unknown, ServerRole.fromServer("   "))
    }

    @Test
    fun `role is read case-insensitively and without spaces`() {
        assertEquals(ServerRole.FoodOwner, ServerRole.fromServer(" food_owner "))
    }

    @Test
    fun `provider is everyone who serves people`() {
        // Владелец заведения и мастер — да: у них есть что показать в
        // «Моих заведениях».
        assertTrue(ServerRole.FoodOwner.isProvider)
        assertTrue(ServerRole.CinemaOwner.isProvider)
        assertTrue(ServerRole.Barber.isProvider)
        assertTrue(ServerRole.Freelancer.isProvider)
    }

    @Test
    fun `plain user admin and unknown are not providers`() {
        // Админка живёт отдельно, а про незнакомую роль гадать нельзя.
        assertFalse(ServerRole.User.isProvider)
        assertFalse(ServerRole.Admin.isProvider)
        assertFalse(ServerRole.Unknown.isProvider)
    }
}
