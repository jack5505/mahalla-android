package uz.mahalla.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Семантические цвета редизайна «Focus» — фиолетовая тема
 * (`design_handoff_mahalla_focus/README.md`, раздел Design Tokens).
 * Material 3 ColorScheme не покрывает success, warning и Soft-варианты — они живут здесь
 * и раздаются через CompositionLocal.
 */
@Immutable
data class MahallaColors(
    val accent: Color,
    val accentSoft: Color,
    val fgMuted: Color,
    val outlineSoft: Color,
    val success: Color,
    val successSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val info: Color,
    val infoSoft: Color,
    val skeleton: Color,
)

val LocalMahallaColors = staticCompositionLocalOf<MahallaColors> {
    error("MahallaColors не предоставлены — оберните экран в MahallaTheme")
}

// Градиент фокус-карточки и приветственного экрана онбординга — не входит в
// ColorScheme (M3 не поддерживает градиентные роли), используется напрямую
// через Brush.linearGradient(FocusGradient) в местах, где макет требует заливку.
val FocusGradientStart = Color(0xFF4F2FC0)
val FocusGradientEnd = Color(0xFF7C5CE6)
val FocusGradientWelcomeEnd = Color(0xFF9D84F0)

val FocusLightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF5B3FC4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8DEFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF6D4FC2),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEFF),
    onSecondaryContainer = Color(0xFF4A2F9E),
    background = Color(0xFFFDF8FF),
    onBackground = Color(0xFF1D1B20),
    surface = Color(0xFFFDF8FF),
    onSurface = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFFF1ECF7),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFFCAC4D0),
    outlineVariant = Color(0xFFE7E2EB),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

val FocusLightColors = MahallaColors(
    accent = Color(0xFF6D4FC2),
    accentSoft = Color(0xFFE8DEFF),
    fgMuted = Color(0xFF625B71),
    outlineSoft = Color(0xFFE7E2EB),
    success = Color(0xFF16704B),
    successSoft = Color(0xFFD5EEE3),
    warning = Color(0xFF8A5D00),
    warningSoft = Color(0xFFFAEBCB),
    info = Color(0xFF2A559C),
    infoSoft = Color(0xFFDAE4F6),
    skeleton = Color(0xFFEDE7F5),
)

// Тёмная тема выведена из того же фиолетового seed (5B3FC4) — тональные пары
// M3 baseline для фиолетового спектра; нейтральные тона (background/surface/
// outline) сохранены из прежней палитры варианта B, они уже фиолетово-тёмные
// и проверены ContrastTest. Primary/secondary в тёмной теме совпадают
// (дизайн не разводит их по тону), это осознанно, не дублирование по ошибке.
val FocusDarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFCBB4E4),
    onPrimary = Color(0xFF2B1B3D),
    primaryContainer = Color(0xFF332542),
    onPrimaryContainer = Color(0xFFEDE0F7),
    secondary = Color(0xFFCBB4E4),
    onSecondary = Color(0xFF2B1B3D),
    secondaryContainer = Color(0xFF332542),
    onSecondaryContainer = Color(0xFFEDE0F7),
    background = Color(0xFF120C1A),
    onBackground = Color(0xFFEFE9F2),
    surface = Color(0xFF1D1429),
    onSurface = Color(0xFFEFE9F2),
    surfaceVariant = Color(0xFF2A1F38),
    onSurfaceVariant = Color(0xFFD9CFE4),
    outline = Color(0xFF3A2D48),
    outlineVariant = Color(0xFF2A1F38),
    error = Color(0xFFF58A83),
    onError = Color(0xFF45100D),
    errorContainer = Color(0xFF401D1B),
    onErrorContainer = Color(0xFFFFDAD7),
)

val FocusDarkColors = MahallaColors(
    accent = Color(0xFFCBB4E4),
    accentSoft = Color(0xFF332542),
    fgMuted = Color(0xFF9C90A8),
    outlineSoft = Color(0xFF2A1F38),
    success = Color(0xFF63CFA1),
    successSoft = Color(0xFF12352A),
    warning = Color(0xFFE5BA57),
    warningSoft = Color(0xFF382D16),
    info = Color(0xFF85AFEE),
    infoSoft = Color(0xFF1A2740),
    skeleton = Color(0xFF2A1F38),
)
