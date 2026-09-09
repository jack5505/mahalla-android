package uz.mahalla.screenshot

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uz.mahalla.R
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaFilterChip
import uz.mahalla.core.ui.components.MahallaListItem
import uz.mahalla.core.ui.components.MahallaTextField
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.ui.theme.MahallaTheme
import uz.mahalla.ui.theme.Spacing

/**
 * Скриншот-тесты темы (эпик 13.2): light/dark × uz/ru.
 *
 * До этого единственной проверкой соответствия макету были `@Preview` — их
 * видно только человеку в Android Studio, и ни одна сборка не падала от того,
 * что цвет уехал или строка не влезла. Здесь тот же набор комбинаций
 * снимается на JVM (Robolectric + Roborazzi), эмулятор не нужен.
 *
 * Что снимаем: не экран целиком, а витрину кита — кнопки, поле, чипы, бейджи,
 * карточку. Экраны меняются каждую неделю, и эталон под каждый пришлось бы
 * переснимать; кит меняется редко, а тема живёт именно в нём.
 *
 * Запуск:
 *   ./gradlew verifyRoborazziDebug   # сравнить с эталонами (падает на разнице)
 *   ./gradlew recordRoborazziDebug   # переснять эталоны после правки темы
 *   ./gradlew compareRoborazziDebug  # картинки-диффы в build/outputs/roborazzi
 *
 * В `testDebugUnitTest` съёмка выключена (Roborazzi без своих задач ничего не
 * делает) — намеренно: рендер шрифтов на macOS и Linux различается в
 * отдельных пикселях, и общий прогон тестов краснел бы у разработчика на
 * маке. Эталоны в репозитории сняты на Linux, как в CI.
 *
 * Плотность `xhdpi` и размер 393×852dp — базовый экран из ТЗ.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeGalleryScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = "uz-w393dp-h852dp-port-notnight-xhdpi")
    fun uzLight() = capture(name = "gallery-uz-light", darkTheme = false)

    @Test
    @Config(qualifiers = "uz-w393dp-h852dp-port-night-xhdpi")
    fun uzDark() = capture(name = "gallery-uz-dark", darkTheme = true)

    @Test
    @Config(qualifiers = "ru-w393dp-h852dp-port-notnight-xhdpi")
    fun ruLight() = capture(name = "gallery-ru-light", darkTheme = false)

    @Test
    @Config(qualifiers = "ru-w393dp-h852dp-port-night-xhdpi")
    fun ruDark() = capture(name = "gallery-ru-dark", darkTheme = true)

    private fun capture(name: String, darkTheme: Boolean) {
        compose.setContent {
            // Тема передаётся параметром, а не через `isSystemInDarkTheme`:
            // квалификатор night нужен ресурсам, а какую схему брать —
            // решение самой темы, и в тесте оно должно быть явным.
            MahallaTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ComponentGallery()
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }
}

/**
 * Витрина кита. Строки — из ресурсов: иначе снимки uz и ru отличались бы
 * только именем файла и тест не поймал бы ни пропавший перевод, ни строку,
 * которая в русском длиннее и не влезает в кнопку.
 */
@Composable
private fun ComponentGallery() {
    Column(
        modifier = Modifier.padding(Spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(Spacing.gap),
    ) {
        MahallaButton(
            text = stringResource(R.string.action_continue),
            onClick = {},
            variant = MahallaButtonVariant.Primary,
        )
        MahallaButton(
            text = stringResource(R.string.action_cancel),
            onClick = {},
            variant = MahallaButtonVariant.Secondary,
        )
        // Выключенная, а не грузящаяся: у грузящейся внутри бесконечная
        // анимация индикатора, и эталон зависел бы от того, на каком кадре
        // остановились часы Robolectric.
        MahallaButton(
            text = stringResource(R.string.action_save),
            onClick = {},
            state = ButtonState.Disabled,
        )
        MahallaTextField(
            value = "",
            onValueChange = {},
            label = stringResource(R.string.search_title),
            placeholder = stringResource(R.string.search_hint),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.item)) {
            MahallaFilterChip(
                label = stringResource(R.string.nav_discovery),
                selected = true,
                onClick = {},
            )
            MahallaFilterChip(
                label = stringResource(R.string.nav_orders),
                selected = false,
                onClick = {},
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.item)) {
            MahallaBadge(text = stringResource(R.string.state_loading), tone = MahallaTone.Info)
            MahallaBadge(
                text = stringResource(R.string.error_no_connection),
                tone = MahallaTone.Error,
            )
        }
        MahallaCard {
            Text(
                text = stringResource(R.string.nav_wallet),
                style = MaterialTheme.typography.titleMedium,
            )
            MahallaListItem(
                title = stringResource(R.string.nav_orders),
                subtitle = stringResource(R.string.action_see_all),
                leadingIcon = Icons.Outlined.Storefront,
            )
        }
    }
}
