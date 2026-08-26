package uz.mahalla.core.ui.preview

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import uz.mahalla.ui.theme.MahallaTheme
import uz.mahalla.ui.theme.Spacing

/**
 * Каждый компонент кита показывается на обеих темах и обоих языках
 * (требование эпика 2). Multipreview-аннотация вместо четырёх `@Preview` на
 * каждой функции: набор комбинаций правится в одном месте.
 */
@Preview(name = "uz · light", locale = "uz", showBackground = true)
@Preview(
    name = "uz · dark",
    locale = "uz",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Preview(name = "ru · light", locale = "ru", showBackground = true)
@Preview(
    name = "ru · dark",
    locale = "ru",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
annotation class ThemeLanguagePreviews

/** Крупный системный шрифт — проверка требования 2.4 «крупный шрифт не ломает вёрстку». */
@Preview(name = "uz · large font", locale = "uz", fontScale = 1.5f, showBackground = true)
@Preview(name = "ru · large font", locale = "ru", fontScale = 1.5f, showBackground = true)
annotation class LargeFontPreviews

/** Фон и отступы превью — чтобы компонент не «висел» на белом вне темы. */
@Composable
fun PreviewSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    MahallaTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = modifier.padding(Spacing.gutter)) {
                content()
            }
        }
    }
}
