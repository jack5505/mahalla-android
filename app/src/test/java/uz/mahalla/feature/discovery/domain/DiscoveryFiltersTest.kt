package uz.mahalla.feature.discovery.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Набор фильтров (эпик 4.3): счётчик, сброс, перевод в параметры запроса. */
class DiscoveryFiltersTest {

    @Test
    fun `default filters are considered empty`() {
        val filters = DiscoveryFilters()

        assertTrue(filters.isDefault)
        assertEquals(0, filters.activeCount)
    }

    @Test
    fun `query and sort do not count as active filters`() {
        // Запрос виден в строке поиска, сортировка есть всегда — бейдж с
        // числом фильтров не должен на них реагировать.
        val filters = DiscoveryFilters(query = "osh", sort = PlaceSort.Rating)

        assertEquals(0, filters.activeCount)
        assertFalse(filters.isDefault)
    }

    @Test
    fun `only a request without any restriction may refresh the offline cache`() {
        // Кэш должен оставаться «всем, что рядом», а не срезом вчерашнего
        // поиска, поэтому запрос считается здесь, в отличие от activeCount.
        assertTrue(DiscoveryFilters().isUnfiltered)
        assertTrue(DiscoveryFilters(sort = PlaceSort.Rating).isUnfiltered)
        assertFalse(DiscoveryFilters(query = "osh").isUnfiltered)
        assertFalse(DiscoveryFilters(openNowOnly = true).isUnfiltered)
        assertFalse(DiscoveryFilters(categories = setOf(PlaceCategory.Food)).isUnfiltered)
    }

    @Test
    fun `every restriction adds one to the counter`() {
        val filters = DiscoveryFilters(
            categories = setOf(PlaceCategory.Food, PlaceCategory.Cinema),
            maxDistanceMeters = 1_000,
            minRating = 4.0,
            openNowOnly = true,
        )

        assertEquals(5, filters.activeCount)
    }

    @Test
    fun `reset keeps the query`() {
        val filters = DiscoveryFilters(
            query = "osh",
            categories = setOf(PlaceCategory.Food),
            openNowOnly = true,
            sort = PlaceSort.Rating,
        )

        val cleared = filters.cleared()

        assertEquals("osh", cleared.query)
        assertEquals(0, cleared.activeCount)
        assertEquals(PlaceSort.Relevance, cleared.sort)
    }

    @Test
    fun `toggling a category adds and removes it`() {
        val once = DiscoveryFilters().toggleCategory(PlaceCategory.Pharmacy)
        val twice = once.toggleCategory(PlaceCategory.Pharmacy)

        assertEquals(setOf(PlaceCategory.Pharmacy), once.categories)
        assertTrue(twice.categories.isEmpty())
    }

    @Test
    fun `api category takes the first selected one`() {
        // Серверный контракт принимает одну категорию; остальные досекаются
        // локально в PlaceFilterEngine.
        val filters = DiscoveryFilters(categories = setOf(PlaceCategory.Cinema, PlaceCategory.Food))

        assertEquals("FOOD", filters.apiCategory())
    }

    @Test
    fun `api category is null without a selection`() {
        assertNull(DiscoveryFilters().apiCategory())
    }

    @Test
    fun `api category is null when only the unknown category is selected`() {
        assertNull(DiscoveryFilters(categories = setOf(PlaceCategory.Other)).apiCategory())
    }
}
