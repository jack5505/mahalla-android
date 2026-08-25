package uz.mahalla.core.ui.text

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Маска телефона и удержание каретки (эпик 2.1). Прыгающий курсор — реальная
 * жалоба к прошлой версии поля (код-ревью PR #19), поэтому позиция каретки
 * проверяется отдельно от текста.
 */
class PhoneFieldFormatterTest {

    @Test
    fun `formats full national number in groups`() {
        assertEquals("90 123 45 67", PhoneFieldFormatter.format("901234567"))
    }

    @Test
    fun `formats partial input progressively`() {
        assertEquals("", PhoneFieldFormatter.format(""))
        assertEquals("9", PhoneFieldFormatter.format("9"))
        assertEquals("90", PhoneFieldFormatter.format("90"))
        assertEquals("90 1", PhoneFieldFormatter.format("901"))
        assertEquals("90 123", PhoneFieldFormatter.format("90123"))
        assertEquals("90 123 4", PhoneFieldFormatter.format("901234"))
        assertEquals("90 123 45 6", PhoneFieldFormatter.format("90123456"))
    }

    @Test
    fun `drops non digits and extra digits`() {
        assertEquals("90 123 45 67", PhoneFieldFormatter.format("+90-123 45 67 89"))
        assertEquals("901234567", PhoneFieldFormatter.digitsOf("90 123 45 678"))
    }

    @Test
    fun `caret stays after the last typed digit`() {
        val result = PhoneFieldFormatter.apply(raw = "9012", caret = 4)

        assertEquals("90 12", result.text)
        assertEquals(5, result.caret)
    }

    @Test
    fun `caret stays put when editing in the middle`() {
        // Пользователь исправил вторую цифру в «90 123 45 67» → каретка после неё.
        val result = PhoneFieldFormatter.apply(raw = "95 123 45 67", caret = 2)

        assertEquals("95 123 45 67", result.text)
        assertEquals(2, result.caret)
    }

    @Test
    fun `caret is zero for empty input`() {
        val result = PhoneFieldFormatter.apply(raw = "", caret = 0)

        assertEquals("", result.text)
        assertEquals(0, result.caret)
    }

    @Test
    fun `paste of full number keeps caret at the end`() {
        val pasted = "+998 90 123 45 67"
        val result = PhoneFieldFormatter.apply(raw = pasted, caret = pasted.length)

        // Первые три цифры вставки — код страны, поле хранит только 9 цифр.
        assertEquals("99 890 12 34", result.text)
        assertEquals(result.text.length, result.caret)
    }

    @Test
    fun `caret out of bounds does not crash`() {
        val result = PhoneFieldFormatter.apply(raw = "901", caret = 99)

        assertEquals("90 1", result.text)
        assertEquals(4, result.caret)
    }
}
