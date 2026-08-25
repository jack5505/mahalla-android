package uz.mahalla.core.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import uz.mahalla.ui.theme.LocalMahallaColors

/**
 * Смысловой тон бейджей, снекбаров и статусов заказов (DESIGN-SYSTEM §состояния).
 * Пары «фон + текст» берутся из [uz.mahalla.ui.theme.MahallaColors] — контраст
 * каждой пары зафиксирован в `ContrastTest`.
 */
enum class MahallaTone {
    Neutral,
    Accent,
    Success,
    Warning,
    Info,
    Error,
}

@Immutable
data class ToneColors(val container: Color, val content: Color)

@Composable
@ReadOnlyComposable
fun MahallaTone.colors(): ToneColors {
    val scheme = MaterialTheme.colorScheme
    val mahalla = LocalMahallaColors.current
    return when (this) {
        MahallaTone.Neutral -> ToneColors(scheme.surfaceVariant, scheme.onSurfaceVariant)
        // На accentSoft контраст самого accent ниже 4.5:1 (см. ContrastTest),
        // поэтому текст бейджа — onSecondaryContainer, а accent остаётся
        // цветом иконок и акцентных элементов.
        MahallaTone.Accent -> ToneColors(mahalla.accentSoft, scheme.onSecondaryContainer)
        MahallaTone.Success -> ToneColors(mahalla.successSoft, mahalla.success)
        MahallaTone.Warning -> ToneColors(mahalla.warningSoft, mahalla.warning)
        MahallaTone.Info -> ToneColors(mahalla.infoSoft, mahalla.info)
        MahallaTone.Error -> ToneColors(scheme.errorContainer, scheme.onErrorContainer)
    }
}
