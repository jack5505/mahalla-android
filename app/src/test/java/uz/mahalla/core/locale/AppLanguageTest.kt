package uz.mahalla.core.locale

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {

    @Test
    fun `uzbek is the default resource language`() {
        assertEquals("uz", AppLanguage.UZBEK.tag)
        assertEquals("ru", AppLanguage.RUSSIAN.tag)
    }

    @Test
    fun `system language has no tag`() {
        assertEquals(null, AppLanguage.SYSTEM.tag)
        assertEquals("", AppLanguage.SYSTEM.storedValue)
        assertEquals(AppLanguage.SYSTEM, AppLanguage.Default)
    }

    @Test
    fun `parses region and script suffixes`() {
        assertEquals(AppLanguage.RUSSIAN, AppLanguage.fromTag("ru"))
        assertEquals(AppLanguage.RUSSIAN, AppLanguage.fromTag("ru-RU"))
        assertEquals(AppLanguage.UZBEK, AppLanguage.fromTag("uz-Latn-UZ"))
        assertEquals(AppLanguage.UZBEK, AppLanguage.fromTag("uz_UZ"))
        assertEquals(AppLanguage.UZBEK, AppLanguage.fromTag("UZ"))
    }

    @Test
    fun `unknown and empty tags fall back to system`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("   "))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("en-US"))
    }

    @Test
    fun `stored value round trips`() {
        AppLanguage.entries.forEach { language ->
            assertEquals(language, AppLanguage.fromTag(language.storedValue))
        }
    }
}
