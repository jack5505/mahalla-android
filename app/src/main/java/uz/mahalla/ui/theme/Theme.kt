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

/**
 * Отступы и размеры — `design_handoff_mahalla_focus/README.md`, раздел
 * «Отступы». `minTouch` держим на 48dp, а не на макетных 44dp — правило кита
 * (`.claude/rules/compose-ui.md`) важнее визуального макета.
 */
object Spacing {
    val gutter = 20.dp // поля экрана
    val onboardingGutter = 24.dp // у онбординга поля шире — так в макете
    val card = 18.dp // внутренний отступ карточки/блока
    val item = 12.dp // вертикальный отступ строки списка
    val gap = 20.dp // расстояние между блоками в колонке (18-22dp)
    val buttonHeight = 46.dp // визуально; цель нажатия всё равно 48dp (MahallaComponentDefaults)
    val fieldHeight = 56.dp
    val chipHeight = 42.dp
    val navHeight = 62.dp
    val minTouch = 48.dp // минимальная цель нажатия, важнее визуальной высоты
}
