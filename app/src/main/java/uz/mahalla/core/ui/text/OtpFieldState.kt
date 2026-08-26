package uz.mahalla.core.ui.text

import androidx.compose.runtime.Immutable

/**
 * Состояние поля OTP (эпик 2.1). Ввод идёт в одно скрытое поле, а ячейки —
 * это отрисовка состояния: так работают автозаполнение SMS и вставка кода
 * целиком, чего не даёт связка из шести отдельных полей.
 */
@Immutable
data class OtpFieldState(
    val code: String = "",
    val length: Int = DEFAULT_LENGTH,
    val isError: Boolean = false,
) {
    init {
        require(length > 0) { "Длина OTP должна быть положительной" }
    }

    val isComplete: Boolean get() = code.length == length

    val filledCount: Int get() = code.length

    /** Индекс ячейки, в которую пойдёт следующая цифра; null — код набран. */
    val focusedIndex: Int? get() = if (isComplete) null else code.length

    /** Только цифры и не длиннее [length]: вставка «код: 123456» тоже сработает. */
    fun onInput(raw: String): OtpFieldState =
        copy(code = raw.filter(Char::isDigit).take(length), isError = false)

    fun digitAt(index: Int): Char? = code.getOrNull(index)

    fun cells(): List<Char?> = List(length) { digitAt(it) }

    fun asError(): OtpFieldState = copy(isError = true)

    fun cleared(): OtpFieldState = copy(code = "", isError = false)

    companion object {
        const val DEFAULT_LENGTH = 6
    }
}
