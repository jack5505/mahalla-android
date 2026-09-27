package uz.mahalla.feature.discovery.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Какие плитки показывать (issue #378): порядок сервера, пропуск неизвестных
 * кодов, фолбэк на зашитый набор.
 */
class PlaceCategoryCatalogTest {

    @Test
    fun `order follows the server, not the enum`() {
        // В перечислении еда раньше аптеки; дашборд решил иначе.
        assertEquals(
            listOf(PlaceCategory.Pharmacy, PlaceCategory.Food),
            PlaceCategoryCatalog.resolve(listOf("PHARMACY", "FOOD")),
        )
    }

    @Test
    fun `codes without a tile in the app are skipped`() {
        assertEquals(
            listOf(PlaceCategory.Food, PlaceCategory.Cinema),
            PlaceCategoryCatalog.resolve(listOf("BAKERY", "FOOD", "SHOP", "CINEMA", "MOSQUE")),
        )
    }

    @Test
    fun `two codes of one category make one tile`() {
        // BARBER и FREELANCER — оба «мастер» в приложении.
        assertEquals(
            listOf(PlaceCategory.Master),
            PlaceCategoryCatalog.resolve(listOf("BARBER", "FREELANCER")),
        )
    }

    @Test
    fun `codes are matched regardless of case`() {
        assertEquals(listOf(PlaceCategory.Food), PlaceCategoryCatalog.resolve(listOf("food")))
    }

    @Test
    fun `an empty cache falls back to the built-in set`() {
        assertEquals(PlaceCategory.selectable, PlaceCategoryCatalog.resolve(emptyList()))
    }

    @Test
    fun `a non-empty list without known codes is honestly empty`() {
        // Дашборд выключил всё, что приложение умеет, — фолбэк тут был бы ложью.
        assertTrue(PlaceCategoryCatalog.resolve(listOf("BAKERY")).isEmpty())
    }
}
