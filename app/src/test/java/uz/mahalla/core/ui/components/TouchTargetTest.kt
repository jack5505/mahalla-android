package uz.mahalla.core.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.ui.theme.Spacing

/**
 * Доступность (эпик 2.4): каждая цель нажатия — не меньше 48dp.
 *
 * Проверка обычным unit-тестом, а не скриншотом: размеры кита объявлены
 * значениями в [MahallaComponentDefaults], и «оптимизация» вроде «кнопка
 * 40dp красивее» упадёт здесь, а не в отзыве в сторе.
 */
class TouchTargetTest {

    @Test
    fun `every declared touch target is at least 48dp`() {
        val tooSmall = MahallaComponentDefaults.touchTargets
            .filterValues { it < MIN_TOUCH_TARGET }
            .map { (name, size) -> "$name = $size" }

        assertTrue("Меньше 48dp: $tooSmall", tooSmall.isEmpty())
    }

    @Test
    fun `minimum touch target matches the design system`() {
        assertEquals(MIN_TOUCH_TARGET, MahallaComponentDefaults.minTouchTarget)
        // Тема объявляет ту же величину — расхождение означало бы два разных
        // «минимума» в одном приложении.
        assertEquals(Spacing.minTouch, MahallaComponentDefaults.minTouchTarget)
    }

    @Test
    fun `visual button height stays smaller than the touch target`() {
        // Макет рисует кнопку 44dp — цель нажатия добирается отступами, а не
        // раздуванием визуальной высоты.
        assertTrue(Spacing.buttonHeight < MahallaComponentDefaults.buttonMinHeight)
    }

    @Test
    fun `otp row is tall enough even with narrow cells`() {
        assertTrue(MahallaComponentDefaults.otpCellWidth < MIN_TOUCH_TARGET)
        assertEquals(MIN_TOUCH_TARGET, MahallaComponentDefaults.otpCellHeight)
    }

    @Test
    fun `kit declares targets for all interactive components`() {
        val expected = setOf(
            "button",
            "field",
            "chip",
            "listItem",
            "iconButton",
            "switchRow",
            "checkboxRow",
            "segment",
            "navItem",
            "otpCell",
        )

        assertEquals(expected, MahallaComponentDefaults.touchTargets.keys)
    }

    private companion object {
        val MIN_TOUCH_TARGET = 48.dp
    }
}
