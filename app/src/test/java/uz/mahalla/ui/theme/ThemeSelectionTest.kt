package uz.mahalla.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MahallaTheme выбирает схему по darkTheme (требование design/android/HANDOFF.md).
 * Выбор вынесен в чистые функции focusColorScheme/focusMahallaColors,
 * поэтому покрывается обычным unit-тестом без Robolectric.
 */
class ThemeSelectionTest {

    @Test
    fun darkThemeReturnsDarkScheme() {
        assertEquals(FocusDarkScheme.background, focusColorScheme(darkTheme = true).background)
    }

    @Test
    fun lightThemeReturnsLightScheme() {
        assertEquals(FocusLightScheme.background, focusColorScheme(darkTheme = false).background)
    }

    @Test
    fun darkThemeReturnsDarkExtraColors() {
        assertEquals(FocusDarkColors, focusMahallaColors(darkTheme = true))
    }

    @Test
    fun lightThemeReturnsLightExtraColors() {
        assertEquals(FocusLightColors, focusMahallaColors(darkTheme = false))
    }
}
