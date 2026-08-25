package uz.mahalla.core.ui.text

/** Текст поля вместе с позицией каретки после применения маски. */
data class MaskedInput(val text: String, val caret: Int)

/**
 * Маска национальной части узбекского номера: `90 123 45 67`.
 *
 * Код страны `+998` в поле не редактируется — он рисуется префиксом, поэтому
 * форматтер видит только девять цифр. Каретка пересчитывается по числу цифр
 * слева от неё, иначе после вставки пробела курсор прыгает в конец строки
 * (замечание код-ревью PR #19 к `PhoneInputScreen`).
 *
 * Чистый Kotlin без Compose — проверяется обычным unit-тестом.
 */
object PhoneFieldFormatter {

    const val NATIONAL_LENGTH = 9

    /** Разбивка национальной части: `90 123 45 67` (как в PhoneNumberValidator). */
    private val GROUPS = listOf(2, 3, 2, 2)

    private const val SEPARATOR = ' '

    fun digitsOf(raw: String): String = raw.filter(Char::isDigit).take(NATIONAL_LENGTH)

    fun format(digits: String): String = buildString {
        val national = digitsOf(digits)
        var start = 0
        for (size in GROUPS) {
            if (start >= national.length) break
            if (start > 0) append(SEPARATOR)
            append(national.substring(start, minOf(start + size, national.length)))
            start += size
        }
    }

    /**
     * Применяет маску к произвольному вводу (набор, вставка, удаление) и
     * возвращает новую позицию каретки.
     */
    fun apply(raw: String, caret: Int): MaskedInput {
        val digits = digitsOf(raw)
        val safeCaret = caret.coerceIn(0, raw.length)
        val digitsBeforeCaret = raw.take(safeCaret).count(Char::isDigit).coerceAtMost(digits.length)
        val text = format(digits)
        return MaskedInput(text, caretAfterDigit(text, digitsBeforeCaret))
    }

    private fun caretAfterDigit(text: String, digitCount: Int): Int {
        if (digitCount <= 0) return 0
        var seen = 0
        text.forEachIndexed { index, char ->
            if (char.isDigit()) {
                seen++
                if (seen == digitCount) return index + 1
            }
        }
        return text.length
    }
}
