package uz.mahalla.core.format

/**
 * Счётчики (лайки, отзывы и т. п.) — те же разряды, что у сумм, иначе на
 * одном экране числа форматируются по-разному.
 */
object CountFormatter {

    /** `12345` → `12 345`. Отрицательное значение на экране не показываем. */
    fun count(value: Long): String = MoneyFormatter.amount(value.coerceAtLeast(0))

    fun count(value: Int): String = count(value.toLong())
}
