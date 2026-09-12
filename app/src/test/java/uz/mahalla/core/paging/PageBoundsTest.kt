package uz.mahalla.core.paging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правило конца страничного списка (issue #142) — одно на весь проект, поэтому
 * проверяется напрямую, а не только через тесты кошелька, заказов и мастеров.
 */
class PageBoundsTest {

    @Test
    fun `last from the server wins over everything else`() {
        // Его считает сервер: он единственный знает про последнюю страницу
        // наверняка.
        assertFalse(hasMorePages(page = 0, totalPages = 10, last = true))
        assertTrue(hasMorePages(page = 9, totalPages = 10, last = false))
    }

    @Test
    fun `without last the page number is compared to totalPages`() {
        assertTrue(hasMorePages(page = 0, totalPages = 3, last = null))
        assertTrue(hasMorePages(page = 1, totalPages = 3, last = null))
        assertFalse(hasMorePages(page = 2, totalPages = 3, last = null))
        // Страница за концом — тоже конец, а не «ещё есть».
        assertFalse(hasMorePages(page = 7, totalPages = 3, last = null))
    }

    @Test
    fun `a missing page number counts as the first one`() {
        assertTrue(hasMorePages(page = null, totalPages = 2, last = null))
        assertFalse(hasMorePages(page = null, totalPages = 1, last = null))
    }

    @Test
    fun `total silence about pages stops the pagination`() {
        // Лучше не показать хвост списка, чем зациклить запрос одной и той же
        // страницы (issue #62).
        assertFalse(hasMorePages(page = null, totalPages = null, last = null))
        assertFalse(hasMorePages(page = 5, totalPages = null, last = null))
    }

    @Test
    fun `an empty page count is the end, not the beginning`() {
        // `totalPages: 0` отдаёт пустой список — следующей страницы у него
        // нет; арифметика `0 + 1 < 0` это и даёт, но зафиксировать стоит.
        assertFalse(hasMorePages(page = 0, totalPages = 0, last = null))
    }
}
