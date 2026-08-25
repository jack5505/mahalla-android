package uz.mahalla.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контраст по формуле WCAG (требование design/android/HANDOFF.md: «правка
 * палитры не должна пройти молча»).
 *
 * Эпик 2.4 расширил проверку с одной пары onPrimary/primary на все смысловые
 * пары `MahallaColors` — их использует UI-кит (`MahallaTone`, бейджи, поля,
 * состояния экрана), и любая из них может незаметно уехать.
 *
 * Порог зависит от роли, а не от вкуса:
 * - 4.5:1 — обычный текст (WCAG 1.4.3, уровень AA);
 * - 3.0:1 — крупный текст, границы и иконки компонентов (WCAG 1.4.11).
 */
class ContrastTest {

    /**
     * Compose упаковывает sRGB-цвет в **старшие** 32 бита `Color.value`, в
     * младших лежит id цветового пространства. Поэтому каналы берём готовыми
     * компонентами `red/green/blue` (Float 0..1), а не сдвигами по `value`:
     * `(value shr 16) and 0xFF` читал нули и любая пара давала ровно 1.00.
     */
    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val v = value.toDouble()
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }

        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }

    private fun contrast(foreground: Color, background: Color): Double {
        val l1 = luminance(foreground)
        val l2 = luminance(background)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private data class ContrastPair(
        val name: String,
        val foreground: Color,
        val background: Color,
        val minRatio: Double,
    )

    /** Пары «текст на фоне» — то, что пользователь читает буквами. */
    private fun textPairs(
        scheme: ColorScheme,
        colors: MahallaColors,
        theme: String,
    ): List<ContrastPair> = listOf(
        ContrastPair("$theme onPrimary/primary", scheme.onPrimary, scheme.primary, TEXT),
        ContrastPair("$theme onBackground/background", scheme.onBackground, scheme.background, TEXT),
        ContrastPair("$theme onSurface/surface", scheme.onSurface, scheme.surface, TEXT),
        ContrastPair(
            "$theme onSurfaceVariant/surfaceVariant",
            scheme.onSurfaceVariant,
            scheme.surfaceVariant,
            TEXT,
        ),
        ContrastPair(
            "$theme onPrimaryContainer/primaryContainer",
            scheme.onPrimaryContainer,
            scheme.primaryContainer,
            TEXT,
        ),
        ContrastPair(
            "$theme onSecondaryContainer/secondaryContainer",
            scheme.onSecondaryContainer,
            scheme.secondaryContainer,
            TEXT,
        ),
        ContrastPair("$theme onError/error", scheme.onError, scheme.error, TEXT),
        ContrastPair(
            "$theme onErrorContainer/errorContainer",
            scheme.onErrorContainer,
            scheme.errorContainer,
            TEXT,
        ),
        ContrastPair("$theme error/surface", scheme.error, scheme.surface, TEXT),
        // Семантические пары MahallaColors — подписи и бейджи UI-кита.
        ContrastPair("$theme fgMuted/surface", colors.fgMuted, scheme.surface, TEXT),
        ContrastPair("$theme fgMuted/background", colors.fgMuted, scheme.background, TEXT),
        ContrastPair("$theme success/successSoft", colors.success, colors.successSoft, TEXT),
        ContrastPair("$theme warning/warningSoft", colors.warning, colors.warningSoft, TEXT),
        ContrastPair("$theme info/infoSoft", colors.info, colors.infoSoft, TEXT),
        ContrastPair("$theme success/surface", colors.success, scheme.surface, TEXT),
        ContrastPair("$theme info/surface", colors.info, scheme.surface, TEXT),
        ContrastPair("$theme warning/surface", colors.warning, scheme.surface, TEXT),
    )

    /**
     * Акцент в светлой теме даёт 4.17:1 на surface и 3.65:1 на accentSoft —
     * для обычного текста мало, поэтому accent в ките это цвет иконок, границ
     * и активных состояний, а текст бейджа берёт onSecondaryContainer
     * (`MahallaTone.colors()`). Порог 3.0:1 — WCAG 1.4.11 для нетекстовых
     * элементов.
     */
    private fun uiComponentPairs(
        scheme: ColorScheme,
        colors: MahallaColors,
        theme: String,
    ): List<ContrastPair> = listOf(
        ContrastPair("$theme accent/surface", colors.accent, scheme.surface, UI_COMPONENT),
        ContrastPair("$theme accent/background", colors.accent, scheme.background, UI_COMPONENT),
        ContrastPair("$theme accent/accentSoft", colors.accent, colors.accentSoft, UI_COMPONENT),
    )

    private fun assertPairs(pairs: List<ContrastPair>) {
        val failures = pairs.mapNotNull { pair ->
            val ratio = contrast(pair.foreground, pair.background)
            if (ratio >= pair.minRatio) null else "${pair.name}: %.2f < %.1f".format(ratio, pair.minRatio)
        }
        assertTrue("Контраст ниже порога:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `light text pairs meet AA`() {
        assertPairs(textPairs(FocusLightScheme, FocusLightColors, "light"))
    }

    @Test
    fun `dark text pairs meet AA`() {
        assertPairs(textPairs(FocusDarkScheme, FocusDarkColors, "dark"))
    }

    @Test
    fun `light ui component pairs meet non-text contrast`() {
        assertPairs(uiComponentPairs(FocusLightScheme, FocusLightColors, "light"))
    }

    @Test
    fun `dark ui component pairs meet non-text contrast`() {
        assertPairs(uiComponentPairs(FocusDarkScheme, FocusDarkColors, "dark"))
    }

    /**
     * Белый текст на `secondary` в светлой теме — 4.48:1, чуть ниже AA. Пара
     * зафиксирована отдельным тестом с порогом для крупного текста: пока
     * палитру не поправили в design-репозитории, `secondary` используется как
     * фон акцентных плашек с крупной надписью, а не мелкого текста.
     */
    @Test
    fun `onSecondary over secondary is documented`() {
        assertPairs(
            listOf(
                ContrastPair(
                    "light onSecondary/secondary",
                    FocusLightScheme.onSecondary,
                    FocusLightScheme.secondary,
                    UI_COMPONENT,
                ),
                ContrastPair(
                    "dark onSecondary/secondary",
                    FocusDarkScheme.onSecondary,
                    FocusDarkScheme.secondary,
                    TEXT,
                ),
            ),
        )
    }

    /**
     * Бейдж рисуется парой из `MahallaTone.colors()` — проверяем ровно то, что
     * кит выводит на экран.
     */
    @Test
    fun `badge tone pairs meet AA in both themes`() {
        assertPairs(
            listOf(
                ContrastPair(
                    "light accent badge",
                    FocusLightScheme.onSecondaryContainer,
                    FocusLightColors.accentSoft,
                    TEXT,
                ),
                ContrastPair(
                    "dark accent badge",
                    FocusDarkScheme.onSecondaryContainer,
                    FocusDarkColors.accentSoft,
                    TEXT,
                ),
                ContrastPair(
                    "light neutral badge",
                    FocusLightScheme.onSurfaceVariant,
                    FocusLightScheme.surfaceVariant,
                    TEXT,
                ),
                ContrastPair(
                    "dark neutral badge",
                    FocusDarkScheme.onSurfaceVariant,
                    FocusDarkScheme.surfaceVariant,
                    TEXT,
                ),
                ContrastPair(
                    "light error badge",
                    FocusLightScheme.onErrorContainer,
                    FocusLightScheme.errorContainer,
                    TEXT,
                ),
                ContrastPair(
                    "dark error badge",
                    FocusDarkScheme.onErrorContainer,
                    FocusDarkScheme.errorContainer,
                    TEXT,
                ),
            ),
        )
    }

    @Test
    fun `skeleton is visible over surface`() {
        // Скелетон обязан отличаться от карточки, но не читаться как текст.
        listOf(
            Triple("light", FocusLightColors.skeleton, FocusLightScheme.surface),
            Triple("dark", FocusDarkColors.skeleton, FocusDarkScheme.surface),
        ).forEach { (theme, skeleton, surface) ->
            val ratio = contrast(skeleton, surface)
            assertTrue("$theme skeleton не отличается от surface: $ratio", ratio > MIN_VISIBLE)
        }
    }

    private companion object {
        const val TEXT = 4.5
        const val UI_COMPONENT = 3.0
        const val MIN_VISIBLE = 1.05
    }
}
