package uz.mahalla.feature.discovery.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.testutil.place

/** Блоки главной (эпик 4.1): что попадает в «рядом» и в «рекомендации». */
class HomeSectionsTest {

    @Test
    fun `nearby is sorted by distance and limited`() {
        val places = (1..10).map { place("p$it", distanceMeters = it * 100) }

        val nearby = HomeSections.nearby(places.reversed())

        assertEquals(HomeSections.SECTION_LIMIT, nearby.size)
        assertEquals(listOf("p1", "p2", "p3", "p4", "p5", "p6"), nearby.map(Place::id))
    }

    @Test
    fun `server marked places are recommended regardless of rating`() {
        val marked = place("marked", rating = 3.0, reviewCount = 2, isRecommended = true)
        val ordinary = place("ordinary", rating = 4.0, reviewCount = 100)

        val recommended = HomeSections.recommended(listOf(ordinary, marked))

        assertEquals(listOf("marked"), recommended.map(Place::id))
    }

    @Test
    fun `high rating alone is not enough without reviews`() {
        // Одна пятёрка — ещё не репутация: без порога по отзывам в
        // рекомендации попадало бы любое только что открывшееся место.
        val fresh = place("fresh", rating = 5.0, reviewCount = 1)
        val proven = place("proven", rating = 4.6, reviewCount = 50)

        val recommended = HomeSections.recommended(listOf(fresh, proven))

        assertEquals(listOf("proven"), recommended.map(Place::id))
    }

    @Test
    fun `marked places go before highly rated ones`() {
        val marked = place("marked", rating = 4.5, reviewCount = 20, isRecommended = true)
        val best = place("best", rating = 5.0, reviewCount = 500)

        val recommended = HomeSections.recommended(listOf(best, marked))

        assertEquals(listOf("marked", "best"), recommended.map(Place::id))
    }

    @Test
    fun `recommendations are limited too`() {
        val places = (1..10).map { place("p$it", rating = 4.9, reviewCount = 100) }

        assertEquals(HomeSections.SECTION_LIMIT, HomeSections.recommended(places).size)
    }

    @Test
    fun `empty input produces empty sections`() {
        assertTrue(HomeSections.nearby(emptyList()).isEmpty())
        assertTrue(HomeSections.recommended(emptyList()).isEmpty())
    }
}
