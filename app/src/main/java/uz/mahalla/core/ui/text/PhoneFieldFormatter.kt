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

    /** Код страны Узбекистана — приходит во вставке из SMS или буфера обмена. */
    private const val COUNTRY_CODE = "998"

    fun digitsOf(raw: String): String = nationalDigits(raw).digits

    /**
     * Цифры вставки без кода страны и ведущих нулей.
     *
     * Префикс снимается только когда цифр больше девяти: набранные вручную
     * `998 12 34 56` — это валидный национальный номер оператора 99, а не код
     * страны, и трогать его нельзя. [dropped] нужен, чтобы после снятия
     * префикса каретка не уехала (см. [apply]).
     */
    private fun nationalDigits(raw: String): NationalDigits {
        val all = raw.filter(Char::isDigit)
        var digits = all
        while (digits.length > NATIONAL_LENGTH) {
            digits = when {
                digits.startsWith(COUNTRY_CODE) -> digits.removePrefix(COUNTRY_CODE)
                digits.startsWith('0') -> digits.drop(1)
                else -> break
            }
        }
        return NationalDigits(digits.take(NATIONAL_LENGTH), all.length - digits.length)
    }

    private data class NationalDigits(val digits: String, val dropped: Int)

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
        val (digits, dropped) = nationalDigits(raw)
        val safeCaret = caret.coerceIn(0, raw.length)
        val digitsBeforeCaret = (raw.take(safeCaret).count(Char::isDigit) - dropped)
            .coerceIn(0, digits.length)
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
