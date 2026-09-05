package uz.mahalla.feature.discovery.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Категории каталога (эпик 4.1): разбор значений сервера и список выбора. */
class PlaceCategoryTest {

    @Test
    fun `all categories with a vertical behind them are selectable`() {
        // Шесть категорий ТЗ плюс одежда: у неё появилась своя вертикаль
        // (issue #108), и до этого `FASHION` попадал в Other — то есть
        // магазины одежды не выбирались ни одним фильтром.
        assertEquals(7, PlaceCategory.selectable.size)
        // Значения перечисления бэкенда (issue #53): именно они уходят в
        // параметр `category` запроса.
        assertEquals(
            listOf("FOOD", "PHARMACY", "HOSPITAL", "CINEMA", "GAMING", "BARBER", "FASHION"),
            PlaceCategory.selectable.map(PlaceCategory::apiValue),
        )
    }

    @Test
    fun `unknown value maps to Other instead of failing`() {
        // Сервер может отдать новую категорию раньше релиза приложения —
        // падать на этом нельзя.
        assertEquals(PlaceCategory.Other, PlaceCategory.fromApi("MOSQUE"))
        assertEquals(PlaceCategory.Other, PlaceCategory.fromApi("MUSEUM"))
        assertEquals(PlaceCategory.Other, PlaceCategory.fromApi(null))
        assertEquals(PlaceCategory.Other, PlaceCategory.fromApi(""))
    }

    @Test
    fun `values from older versions of the app are still understood`() {
        // В кэше Room и в прежних ответах лежат «food», «playground»,
        // «master» — после обновления они не должны превратиться в Other.
        assertEquals(PlaceCategory.Food, PlaceCategory.fromApi("food"))
        assertEquals(PlaceCategory.Playground, PlaceCategory.fromApi("playground"))
        assertEquals(PlaceCategory.Master, PlaceCategory.fromApi("master"))
        // «CLOTHING» — второе написание одежды: так она называется в поле
        // `vertical` заказа (issue #108).
        assertEquals(PlaceCategory.Fashion, PlaceCategory.fromApi("CLOTHING"))
        assertEquals(PlaceCategory.Fashion, PlaceCategory.fromApi("FASHION"))
    }

    @Test
    fun `backend categories map onto the six of the specification`() {
        assertEquals(PlaceCategory.Playground, PlaceCategory.fromApi("GAMING"))
        assertEquals(PlaceCategory.Master, PlaceCategory.fromApi("BARBER"))
        assertEquals(PlaceCategory.Master, PlaceCategory.fromApi("FREELANCER"))
    }

    @Test
    fun `parsing ignores case and padding`() {
        assertEquals(PlaceCategory.Pharmacy, PlaceCategory.fromApi("  PHARMACY "))
    }

    @Test
    fun `Other is not selectable and has no api value`() {
        assertTrue(PlaceCategory.Other !in PlaceCategory.selectable)
        assertNull(PlaceCategory.apiValueOrNull(PlaceCategory.Other))
        assertNotNull(PlaceCategory.apiValueOrNull(PlaceCategory.Food))
    }

    @Test
    fun `every category has a label and an icon`() {
        PlaceCategory.entries.forEach { category ->
            assertTrue("нет строки у $category", category.labelRes != 0)
            assertNotNull("нет иконки у $category", category.icon)
        }
    }
}
