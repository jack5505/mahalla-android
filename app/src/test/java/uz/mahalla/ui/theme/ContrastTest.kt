package uz.mahalla.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контраст onPrimary/primary >= 4.5:1 по формуле WCAG (требование design/android/HANDOFF.md:
 * «проверка контраста onPrimary к primary >= 4.5:1 — обычный unit-тест по формуле WCAG,
 * чтобы правка палитры не прошла молча»).
 */
class ContrastTest {

    private fun luminance(argb: Long): Double {
        fun channel(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }

        val r = channel(((argb shr 16) and 0xFF).toInt())
        val g = channel(((argb shr 8) and 0xFF).toInt())
        val b = channel((argb and 0xFF).toInt())
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun contrast(fg: Long, bg: Long): Double {
        val l1 = luminance(fg)
        val l2 = luminance(bg)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    @Test
    fun lightSchemeOnPrimaryOverPrimaryIsAtLeastFourPointFive() {
        val ratio = contrast(
            FocusLightScheme.onPrimary.value.toLong(),
            FocusLightScheme.primary.value.toLong(),
        )
        assertTrue("light onPrimary/primary = $ratio", ratio >= 4.5)
    }

    @Test
    fun darkSchemeOnPrimaryOverPrimaryIsAtLeastFourPointFive() {
        val ratio = contrast(
            FocusDarkScheme.onPrimary.value.toLong(),
            FocusDarkScheme.primary.value.toLong(),
        )
        assertTrue("dark onPrimary/primary = $ratio", ratio >= 4.5)
    }
}
