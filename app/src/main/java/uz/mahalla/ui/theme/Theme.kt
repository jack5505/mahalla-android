package uz.mahalla.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

// Выбор схемы вынесен в чистые функции, чтобы покрыть его unit-тестами
// (см. app/src/test/java/uz/mahalla/ui/theme/ThemeSelectionTest.kt).
fun focusColorScheme(darkTheme: Boolean): ColorScheme =
    if (darkTheme) FocusDarkScheme else FocusLightScheme

fun focusMahallaColors(darkTheme: Boolean): MahallaColors =
    if (darkTheme) FocusDarkColors else FocusLightColors

/**
 * Тема варианта B «Focus». Dynamic Color (Material You) осознанно не включаем:
 * палитра — часть бренда (ТЗ §1).
 */
@Composable
fun MahallaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalMahallaColors provides focusMahallaColors(darkTheme)) {
        MaterialTheme(
            colorScheme = focusColorScheme(darkTheme),
            typography = MahallaTypography,
            shapes = FocusShapes,
            content = content,
        )
    }
}

/** Отступы и размеры по макету — DESIGN-SYSTEM.md §3. */
object Spacing {
    val gutter = 14.dp // поля экрана
    val card = 12.dp // внутренний отступ карточки
    val item = 10.dp // вертикальный отступ строки списка
    val gap = 10.dp // расстояние между блоками в колонке
    val buttonHeight = 44.dp
    val fieldHeight = 46.dp
    val chipHeight = 28.dp
    val navHeight = 58.dp
    val minTouch = 48.dp // минимальная цель нажатия, важнее визуальной высоты
}
