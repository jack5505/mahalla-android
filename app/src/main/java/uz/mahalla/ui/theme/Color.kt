package uz.mahalla.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Семантические цвета варианта B «Focus» (design/android/HANDOFF.md).
 * Material 3 ColorScheme не покрывает success/warning/*Soft — они живут здесь
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

val FocusLightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF2B1B3D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDED5E6),
    onPrimaryContainer = Color(0xFF1E1329),
    secondary = Color(0xFFC4552E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFBE3DA),
    onSecondaryContainer = Color(0xFF4E1D0C),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1E1329),
    surface = Color(0xFFFBF6EF),
    onSurface = Color(0xFF1E1329),
    surfaceVariant = Color(0xFFEFE9F2),
    onSurfaceVariant = Color(0xFF3B2B4C),
    outline = Color(0xFFD9D2DE),
    outlineVariant = Color(0xFFEAE4EF),
    error = Color(0xFFA82521),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFADEDC),
    onErrorContainer = Color(0xFF52100E),
)

val FocusLightColors = MahallaColors(
    accent = Color(0xFFC4552E),
    accentSoft = Color(0xFFFBE3DA),
    fgMuted = Color(0xFF6E6478),
    outlineSoft = Color(0xFFEAE4EF),
    success = Color(0xFF16704B),
    successSoft = Color(0xFFD5EEE3),
    warning = Color(0xFF8A5D00),
    warningSoft = Color(0xFFFAEBCB),
    info = Color(0xFF2A559C),
    infoSoft = Color(0xFFDAE4F6),
    skeleton = Color(0xFFE9E4EE),
)

val FocusDarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFE8704A),
    onPrimary = Color(0xFF2B0F04),
    primaryContainer = Color(0xFF4A2318),
    onPrimaryContainer = Color(0xFFFFD9CC),
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
