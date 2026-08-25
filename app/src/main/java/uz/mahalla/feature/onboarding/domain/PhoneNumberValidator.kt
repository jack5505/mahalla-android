package uz.mahalla.feature.onboarding.domain

import javax.inject.Inject

/**
 * Узбекский номер телефона: `+998` + 9 цифр, где первые две — код оператора.
 *
 * Чистый домен без Android — тестируется на JVM и переиспользуется и в
 * онбординге, и в профиле.
 */
class PhoneNumberValidator @Inject constructor() {

    /**
     * Оставляет только цифры национальной части.
     *
     * Сначала снимается видимый префикс `+998` — поле ввода всегда содержит
     * его, и без этого шага `+998` сам превратился бы в номер `99 8…`.
     * Дальше код страны отрезается лишь когда цифр больше девяти: иначе
     * валидный номер оператора `99` (например `998 12 34 56`) был бы принят
     * за `+998` и обрезан.
     */
    fun nationalDigits(raw: String): String {
        val withoutVisiblePrefix = raw.trim().removePrefix(PLUS + COUNTRY_CODE)
        val digits = withoutVisiblePrefix.filter(Char::isDigit)
        val national = if (digits.length > NATIONAL_LENGTH && digits.startsWith(COUNTRY_CODE)) {
            digits.removePrefix(COUNTRY_CODE)
        } else {
            digits
        }
        return national.take(NATIONAL_LENGTH)
    }

    fun isComplete(nationalDigits: String): Boolean =
        nationalDigits.length == NATIONAL_LENGTH

    fun isValid(nationalDigits: String): Boolean =
        isComplete(nationalDigits) && nationalDigits.take(OPERATOR_CODE_LENGTH) in OPERATOR_CODES

    /** Прогрессивное форматирование по мере ввода: `+998 90 123 45 67`. */
    fun format(nationalDigits: String): String = buildString {
        append(PLUS).append(COUNTRY_CODE)
        val digits = nationalDigits.take(NATIONAL_LENGTH)
        if (digits.isEmpty()) return@buildString
        GROUPS.fold(0) { start, size ->
            if (start >= digits.length) return@buildString
            append(' ').append(digits.substring(start, minOf(start + size, digits.length)))
            start + size
        }
    }

    fun toE164(nationalDigits: String): String = PLUS + COUNTRY_CODE + nationalDigits

    companion object {
        const val COUNTRY_CODE = "998"
        const val NATIONAL_LENGTH = 9
        private const val PLUS = "+"
        private const val OPERATOR_CODE_LENGTH = 2

        /** Разбивка национальной части: `90 123 45 67`. */
        private val GROUPS = listOf(2, 3, 2, 2)

        /** Коды операторов Узбекистана (мобильные + городские серии). */
        private val OPERATOR_CODES = setOf(
            "20", "33", "50", "55", "61", "62", "63", "65", "66", "67", "69",
            "70", "71", "72", "73", "74", "75", "76", "77", "78", "79",
            "88", "90", "91", "93", "94", "95", "97", "98", "99",
        )
    }
}
