package uz.mahalla.feature.place.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.testutil.place

/**
 * Вертикали карточки выводятся из категории (issue #71).
 *
 * До этого набор всегда был пустым: серверных флагов
 * `hasQueue`/`hasBooking`/`hasOrdering` в реальном контракте нет (issue #53), и
 * на карточке не показывалось **ни одного** действия вертикали.
 */
class PlaceCapabilitiesTest {

    @Test
    fun `master gets the queue - the form of the service order`() {
        val capabilities = PlaceCapabilities.forCategory(PlaceCategory.Master)

        assertTrue(capabilities.queue)
        assertFalse(capabilities.booking)
        assertFalse(capabilities.ordering)
    }

    @Test
    fun `categories without a screen get no buttons`() {
        // Кнопка, ведущая в никуда, хуже её отсутствия: пути `FoodApi`
        // расходятся с бэкендом, у игровых зон и кино экранов нет вовсе.
        listOf(
            PlaceCategory.Food,
            PlaceCategory.Playground,
            PlaceCategory.Cinema,
            PlaceCategory.Hospital,
            PlaceCategory.Pharmacy,
            PlaceCategory.Other,
        ).forEach { category ->
            assertEquals(
                category.name,
                PlaceCapabilities(),
                PlaceCapabilities.forCategory(category),
            )
        }
    }

    @Test
    fun `queue is the primary action of a master`() {
        val place = place(id = "p-1", category = PlaceCategory.Master)
        val actions = PlaceActions.resolve(
            capabilities = PlaceCapabilities.forCategory(place.category),
            contacts = PlaceContacts(phone = "+998901234567"),
            place = place,
        )

        assertEquals(PlaceAction.Queue, PlaceActions.primary(actions))
    }
}
