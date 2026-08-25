package uz.mahalla.core.format

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Форматирование сумм (эпик 1.5). Суммы в проекте — целые сумы (UZS не
 * использует копейки в рознице), хранятся в [Long].
 *
 * Разделитель разрядов — неразрывный пробел, чтобы сумма не переносилась по
 * строкам. Символы берём из [Locale.ROOT], а не из системной локали: иначе
 * в некоторых локалях появятся другие цифры/разделители и вывод перестанет
 * совпадать с макетом.
 *
 * Выводить полученную строку нужно стилем с `tnum`
 * (см. `MahallaTypography` / `TabularNums`), иначе цифры «прыгают».
 */
object MoneyFormatter {

    /**
     * Неразрывный пробел U+00A0 — разделитель разрядов и отбивка валюты.
     * Задан кодом, а не литералом, чтобы его нельзя было спутать с обычным
     * пробелом при чтении/правке файла.
     */
    val GROUPING_SEPARATOR: Char = Char(0x00A0)

    private fun formatter(): DecimalFormat = DecimalFormat(
        "#,##0",
        DecimalFormatSymbols(Locale.ROOT).apply { groupingSeparator = GROUPING_SEPARATOR },
    )

    /** `1234567` → `1 234 567` (с неразрывными пробелами). */
    fun amount(sum: Long): String = formatter().format(sum)

    /** `1234567`, `"so'm"` → `1 234 567 so'm`. Подпись валюты — из ресурсов. */
    fun withCurrency(sum: Long, currencyLabel: String): String =
        amount(sum) + GROUPING_SEPARATOR + currencyLabel

    /** Знак всегда явный — для истории кошелька: `+1 000` / `-1 000`. */
    fun signedAmount(sum: Long): String = if (sum > 0) "+" + amount(sum) else amount(sum)
}
