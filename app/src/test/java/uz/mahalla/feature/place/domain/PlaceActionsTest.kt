package uz.mahalla.feature.place.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.feature.discovery.domain.GeoPoint
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.testutil.place

/** Действия карточки (эпик 4.4): показываем только выполнимое. */
class PlaceActionsTest {

    @Test
    fun `actions follow the layout order`() {
        val actions = PlaceActions.resolve(
            capabilities = PlaceCapabilities(queue = true, booking = true, ordering = true),
            contacts = PlaceContacts(phone = "+998901234567"),
            place = place("p", point = GeoPoint(41.31, 69.28)),
        )

        assertEquals(
            listOf(
                PlaceAction.Queue,
                PlaceAction.Booking,
                PlaceAction.Order,
                PlaceAction.Call,
                PlaceAction.Route,
            ),
            actions,
        )
    }

    @Test
    fun `call is hidden without a phone number`() {
        // Кнопка, ведущая в никуда, хуже отсутствующей кнопки.
        val actions = PlaceActions.resolve(
            capabilities = PlaceCapabilities(),
            contacts = PlaceContacts(phone = null),
            place = place("p"),
        )

        assertTrue(PlaceAction.Call !in actions)
    }

    @Test
    fun `blank phone counts as no phone`() {
        val actions = PlaceActions.resolve(
            capabilities = PlaceCapabilities(),
            contacts = PlaceContacts(phone = "   "),
            place = place("p"),
        )

        assertTrue(PlaceAction.Call !in actions)
    }

    @Test
    fun `route is hidden without coordinates`() {
        val actions = PlaceActions.resolve(
            capabilities = PlaceCapabilities(),
            contacts = PlaceContacts(),
            place = place("p", point = null),
        )

        assertTrue(actions.isEmpty())
    }

    @Test
    fun `capabilities come from the server, not from the category`() {
        // Кафе без доставки не должно предлагать заказ только потому, что оно
        // относится к категории «еда».
        val actions = PlaceActions.resolve(
            capabilities = PlaceCapabilities(ordering = false),
            contacts = PlaceContacts(),
            place = place("cafe"),
        )

        assertTrue(PlaceAction.Order !in actions)
    }

    @Test
    fun `primary action is the first available one`() {
        assertEquals(
            PlaceAction.Booking,
            PlaceActions.primary(listOf(PlaceAction.Booking, PlaceAction.Call)),
        )
        assertNull(PlaceActions.primary(emptyList()))
    }

    @Test
    fun `details expose the resolved actions`() {
        val details = PlaceDetails(
            place = place("p", point = GeoPoint(41.31, 69.28)),
            capabilities = PlaceCapabilities(queue = true),
        )

        assertEquals(listOf(PlaceAction.Queue, PlaceAction.Route), details.actions)
    }

    @Test
    fun `queue is offered to barbers and nothing is offered to the rest`() {
        // Флагов «что место умеет» в контракте нет (issue #53): вертикаль
        // следует из категории, и до issue #96 ни одна из них не включалась.
        // Бронь добавилась в issue #97: у мастера это второй способ попасть к
        // нему — не «прямо сейчас», а на выбранное время.
        assertEquals(
            PlaceCapabilities(queue = true, booking = true),
            PlaceCapabilities.of(PlaceCategory.Master),
        )

        listOf(
            PlaceCategory.Food,
            PlaceCategory.Pharmacy,
            PlaceCategory.Hospital,
            PlaceCategory.Cinema,
            PlaceCategory.Playground,
            PlaceCategory.Other,
        ).forEach {
            // Кнопка, ведущая в никуда, хуже отсутствующей: услуги и записи
            // бэкенд отдаёт только у мастеров (`barber-services`), а
            // «Заказать» — вне объёма этих задач.
            assertEquals(it.name, PlaceCapabilities(), PlaceCapabilities.of(it))
        }
    }

    @Test
    fun `a barber card shows taking a ticket as the primary action`() {
        val actions = PlaceActions.resolve(
            capabilities = PlaceCapabilities.of(PlaceCategory.Master),
            contacts = PlaceContacts(phone = "+998901234567"),
            place = place("p"),
        )

        // Очередь остаётся главной: «прийти сейчас» — то, за чем в
        // парикмахерскую заходят чаще, а запись на время стоит рядом второй
        // кнопкой (issue #97).
        assertEquals(PlaceAction.Queue, PlaceActions.primary(actions))
        assertEquals(
            listOf(PlaceAction.Queue, PlaceAction.Booking, PlaceAction.Call),
            actions,
        )
    }
}
