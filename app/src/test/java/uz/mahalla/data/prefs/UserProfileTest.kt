package uz.mahalla.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Аватар-заглушка в шапке профиля (issue #61). */
class UserProfileTest {

    @Test
    fun `initials take the first letters of the first two words`() {
        assertEquals("JS", UserProfile(fullName = "Jahongir Sabirov").initials())
        assertEquals("АУ", UserProfile(fullName = "Алишер Усмонов Бахтиёрович").initials())
        assertEquals("J", UserProfile(fullName = "  Jahongir  ").initials())
    }

    @Test
    fun `no name means no initials`() {
        // Экран рисует иконку человека: пустой круг ничего не сообщает, а
        // выдумывать инициалы из номера телефона нечестно.
        assertEquals("", UserProfile(phone = "+998901234567").initials())
        assertEquals("", UserProfile(fullName = "   ").initials())
    }

    @Test
    fun `initials do not depend on the device locale`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr"))
        try {
            // На турецкой локали `i` уезжает в `İ` — как и промокод в эпике 5.
            assertEquals("I", UserProfile(fullName = "islom").initials())
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `empty profile is recognisable`() {
        assertTrue(UserProfile().isEmpty)
        assertFalse(UserProfile(phone = "+998901234567").isEmpty)
    }
}
