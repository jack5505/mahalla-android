package uz.mahalla.feature.discovery.domain

import java.util.Locale

/**
 * История поиска (эпик 4.3) — чистые правила над списком строк.
 *
 * Хранение (DataStore) намеренно отделено: список склеивается в одну строку
 * через [SEPARATOR], потому что `stringSetPreferencesKey` теряет порядок, а
 * порядок здесь и есть смысл истории.
 */
object SearchHistory {

    /** Больше десяти строк в макет не помещается, и они уже не нужны. */
    const val MAX_SIZE = 10

    /**
     * Символ-разделитель. Перевод строки в поисковый запрос попасть не может
     * (поле однострочное), поэтому экранирование не нужно — но всё, что
     * содержит его после чтения с диска, отбрасывается при разборе.
     */
    const val SEPARATOR = '\n'

    /**
     * Новый запрос — в начало, дубли (без учёта регистра) удаляются, хвост
     * обрезается до [MAX_SIZE]. Пустой запрос историю не меняет.
     */
    fun add(history: List<String>, query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return history
        val rest = history.filterNot { it.equalsIgnoringCase(trimmed) }
        return (listOf(trimmed) + rest).take(MAX_SIZE)
    }

    fun remove(history: List<String>, query: String): List<String> =
        history.filterNot { it.equalsIgnoringCase(query.trim()) }

    fun encode(history: List<String>): String = history
        .take(MAX_SIZE)
        .joinToString(separator = SEPARATOR.toString())

    /**
     * `distinctBy` — страховка от диска, а не от [add]: строку в DataStore мог
     * записать прежний формат или другая версия приложения, а список рисуется
     * `items(key = { it })` — дубликат ключа роняет LazyColumn.
     */
    fun decode(stored: String?): List<String> = stored
        .orEmpty()
        .split(SEPARATOR)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinctBy { it.lowercase(Locale.ROOT) }
        .take(MAX_SIZE)

    private fun String.equalsIgnoringCase(other: String): Boolean =
        lowercase(Locale.ROOT) == other.lowercase(Locale.ROOT)
}
