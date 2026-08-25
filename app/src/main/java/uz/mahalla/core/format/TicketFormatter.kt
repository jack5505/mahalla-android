package uz.mahalla.core.format

import java.util.Locale

/**
 * Номера талонов очереди (эпик 1.5): буква сектора + трёхзначный номер.
 * Выводится стилем с `tnum`, иначе номер «дёргается» при обновлении очереди.
 */
object TicketFormatter {

    private const val MIN_DIGITS = 3

    /** `'A'`, `42` → `A-042`; номер длиннее трёх цифр не обрезается. */
    fun format(sector: Char, number: Int): String =
        String.format(Locale.ROOT, "%s-%0${MIN_DIGITS}d", sector, number)

    /** Разбор строки талона обратно: `A-042` → `'A' to 42`, иначе `null`. */
    fun parse(ticket: String): Pair<Char, Int>? {
        val separator = ticket.indexOf('-')
        if (separator != 1) return null
        val number = ticket.substring(separator + 1).toIntOrNull() ?: return null
        return ticket[0] to number
    }
}
