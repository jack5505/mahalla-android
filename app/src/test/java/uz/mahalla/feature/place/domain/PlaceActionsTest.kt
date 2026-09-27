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
            capabilities = PlaceCapabilities(
                queue = true,
                booking = true,
                ordering = true,
                products = true,
            ),
            contacts = PlaceContacts(phone = "+998901234567"),
            place = place("p", point = GeoPoint(41.31, 69.28)),
        )

        assertEquals(
            listOf(
                PlaceAction.Queue,
                PlaceAction.Booking,
                PlaceAction.Order,
                PlaceAction.Products,
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
    fun `ordering can still be turned off explicitly`() {
        // `PlaceCapabilities` не привязан к категории насильно: конструктор
        // по умолчанию выключен, и `resolve` этого не подменяет.
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
    fun `each category gets the vertical it has a screen for, the rest get nothing`() {
        // Флагов «что место умеет» в контракте нет (issue #53): вертикаль
        // следует из категории, и до issue #96 ни одна из них не включалась.
        // Бронь добавилась в issue #97: у мастера это второй способ попасть к
        // нему — не «прямо сейчас», а на выбранное время.
        assertEquals(
            PlaceCapabilities(queue = true, booking = true),
            PlaceCapabilities.of(PlaceCategory.Master),
        )

        // Игровые клубы добавились в issue #98: зона клуба — свой
        // контроллер (`gaming-controller`) и свой экран, а не запись на
        // время к мастеру.
        assertEquals(
            PlaceCapabilities(gaming = true),
            PlaceCapabilities.of(PlaceCategory.Playground),
        )

        // Больницы добавились в issue #99: у них своя запись — к врачу
        // (`hospital-controller`), а не на услугу заведения.
        assertEquals(
            PlaceCapabilities(doctors = true),
            PlaceCapabilities.of(PlaceCategory.Hospital),
        )

        // Кино добавилось в issue #106: афиша, сеансы и билет
        // (`cinema-controller`).
        assertEquals(
            PlaceCapabilities(cinema = true),
            PlaceCapabilities.of(PlaceCategory.Cinema),
        )

        // Одежда добавилась в issue #108: витрина магазина с корзиной на
        // сервере — своя вертикаль, а не «Заказать» из «Еды».
        assertEquals(
            PlaceCapabilities(shopping = true),
            PlaceCapabilities.of(PlaceCategory.Fashion),
        )

        // Витрина аптеки (issue #100) — действие, которое ничего не начинает:
        // товары бэкенд отдаёт, а заказать их нечем, и `ordering` тут остаётся
        // выключенным намеренно.
        assertEquals(
            PlaceCapabilities(products = true),
            PlaceCapabilities.of(PlaceCategory.Pharmacy),
        )

        // Еда добавилась в issue #335: меню заведения (`food/…/menu`) — до
        // этого `ordering` не включался ни для одной категории, и первый
        // заказ был недостижим с карточки места.
        assertEquals(
            PlaceCapabilities(ordering = true),
            PlaceCapabilities.of(PlaceCategory.Food),
        )

        // Кнопка, ведущая в никуда, хуже отсутствующей: у [Other] нет своего
        // контроллера вовсе — категория ещё не появилась в приложении.
        assertEquals(PlaceCapabilities(), PlaceCapabilities.of(PlaceCategory.Other))
    }

    @Test
    fun `a gaming club card shows the zone as the primary action`() {
        val actions = PlaceActions.resolve(
            capabilities = PlaceCapabilities.of(PlaceCategory.Playground),
            contacts = PlaceContacts(phone = "+998901234567"),
            place = place("p"),
        )

        // Именно `Gaming`, а не `Booking`: последнее ведёт к услугам мастера,
        // которых у клуба нет (issue #97 против issue #98).
        assertEquals(listOf(PlaceAction.Gaming, PlaceAction.Call), actions)
        assertEquals(PlaceAction.Gaming, PlaceActions.primary(actions))
        assertTrue(PlaceAction.Booking !in actions)
    }

    @Test
    fun `a pharmacy card offers the showcase and never a purchase`() {
        val actions = PlaceActions.resolve(
            capabilities = PlaceCapabilities.of(PlaceCategory.Pharmacy),
            contacts = PlaceContacts(phone = "+998901234567"),
            place = place("p"),
        )

        // «Купить» здесь быть не должно: своей ручки заказа
        // `pharmacy-controller` не отдаёт, и корзину аптеки бэкенду нечем
        // принять (issue #100).
        assertEquals(listOf(PlaceAction.Products, PlaceAction.Call), actions)
        assertEquals(PlaceAction.Products, PlaceActions.primary(actions))
        assertTrue(PlaceAction.Order !in actions)
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

    @Test
    fun `a hospital card shows the doctor as its primary action`() {
        val actions = PlaceActions.resolve(
            capabilities = PlaceCapabilities.of(PlaceCategory.Hospital),
            contacts = PlaceContacts(phone = "+998901234567"),
            place = place("p"),
        )

        // Запись к врачу — единственное, что больница умеет в приложении:
        // очередь и бронь у неё выключены, вести им некуда (issue #99).
        assertEquals(PlaceAction.Doctor, PlaceActions.primary(actions))
        assertEquals(listOf(PlaceAction.Doctor, PlaceAction.Call), actions)
    }

    @Test
    fun `a cinema card shows the ticket as its primary action`() {
        val actions = PlaceActions.resolve(
            capabilities = PlaceCapabilities.of(PlaceCategory.Cinema),
            contacts = PlaceContacts(phone = "+998901234567"),
            place = place("p"),
        )

        // Билет — единственное, что кинотеатр умеет в приложении: очередь,
        // бронь и заказ у него выключены, вести им некуда (issue #106).
        assertEquals(PlaceAction.Cinema, PlaceActions.primary(actions))
        assertEquals(listOf(PlaceAction.Cinema, PlaceAction.Call), actions)
    }

    @Test
    fun `a clothing store card leads to its catalog`() {
        val actions = PlaceActions.resolve(
            capabilities = PlaceCapabilities.of(PlaceCategory.Fashion),
            contacts = PlaceContacts(phone = "+998901234567"),
            place = place("p"),
        )

        // Витрина — единственное, что магазин одежды умеет в приложении:
        // очередь, бронь и «Заказать» у него выключены (issue #108).
        assertEquals(PlaceAction.Shop, PlaceActions.primary(actions))
        assertEquals(listOf(PlaceAction.Shop, PlaceAction.Call), actions)
    }

    @Test
    fun `a food place card shows ordering as its primary action`() {
        val actions = PlaceActions.resolve(
            capabilities = PlaceCapabilities.of(PlaceCategory.Food),
            contacts = PlaceContacts(phone = "+998901234567"),
            place = place("p"),
        )

        // Меню — единственное, что заведение общепита умеет в приложении:
        // до issue #335 `ordering` не включался ни для одной категории, и
        // первое «Заказать» было недостижимо с карточки места.
        assertEquals(PlaceAction.Order, PlaceActions.primary(actions))
        assertEquals(listOf(PlaceAction.Order, PlaceAction.Call), actions)
    }
}
