package uz.mahalla.feature.discovery.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** История поиска (эпик 4.3): порядок, дубли, предел и сериализация. */
class SearchHistoryTest {

    @Test
    fun `new query goes first`() {
        val history = SearchHistory.add(listOf("osh", "dorixona"), "kino")

        assertEquals(listOf("kino", "osh", "dorixona"), history)
    }

    @Test
    fun `repeated query moves up instead of duplicating`() {
        val history = SearchHistory.add(listOf("osh", "dorixona"), "dorixona")

        assertEquals(listOf("dorixona", "osh"), history)
    }

    @Test
    fun `duplicates are matched ignoring case and spaces`() {
        val history = SearchHistory.add(listOf("Osh"), "  osh  ")

        assertEquals(listOf("osh"), history)
    }

    @Test
    fun `blank query does not change the history`() {
        val original = listOf("osh")

        assertEquals(original, SearchHistory.add(original, "   "))
        assertEquals(original, SearchHistory.add(original, ""))
    }

    @Test
    fun `history is capped and drops the oldest entry`() {
        val full = (1..SearchHistory.MAX_SIZE).map { "q$it" }

        val history = SearchHistory.add(full, "new")

        assertEquals(SearchHistory.MAX_SIZE, history.size)
        assertEquals("new", history.first())
        assertTrue("q${SearchHistory.MAX_SIZE}" !in history)
    }

    @Test
    fun `remove deletes only the requested query`() {
        val history = SearchHistory.remove(listOf("osh", "kino"), "OSH")

        assertEquals(listOf("kino"), history)
    }

    @Test
    fun `encode and decode round trip`() {
        val history = listOf("osh", "dorixona 24", "kino")

        assertEquals(history, SearchHistory.decode(SearchHistory.encode(history)))
    }

    @Test
    fun `decoding a missing or broken value yields an empty history`() {
        assertTrue(SearchHistory.decode(null).isEmpty())
        assertTrue(SearchHistory.decode("").isEmpty())
        assertEquals(listOf("osh"), SearchHistory.decode("\n\n  osh  \n\n"))
    }

    @Test
    fun `decoding drops duplicates that came from disk`() {
        // Список рисуется `items(key = { it })` — дубликат ключа роняет
        // LazyColumn, а строку мог записать прежний формат хранения.
        assertEquals(listOf("osh", "kino"), SearchHistory.decode("osh\nOsh\nkino\nosh"))
    }

    @Test
    fun `decoding never returns more than the cap`() {
        val stored = (1..50).joinToString(separator = SearchHistory.SEPARATOR.toString()) { "q$it" }

        assertEquals(SearchHistory.MAX_SIZE, SearchHistory.decode(stored).size)
    }
}
