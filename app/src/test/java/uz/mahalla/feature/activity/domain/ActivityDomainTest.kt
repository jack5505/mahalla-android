package uz.mahalla.feature.activity.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure
import java.time.Instant

/**
 * Домен «моих активностей» (issue #73, задача T7): приведение статусов пяти
 * источников к общему виду, отбор по вкладке и порядок списка.
 *
 * Цена ошибки здесь — пропавшая из списка активность, то есть заказ, за
 * который человек заплатил и которого не видит. Поэтому правила проверяются
 * тестом, а не читаются глазами по композаблу.
 */
class ActivityDomainTest {

    // --- Статусы ---

    @Test
    fun `order statuses split into active and history`() {
        assertEquals(ActivityStatus.Placed, ActivityStatus.ofOrder("NEW"))
        assertEquals(ActivityStatus.Confirmed, ActivityStatus.ofOrder("ACCEPTED"))
        assertEquals(ActivityStatus.InProgress, ActivityStatus.ofOrder("PREPARING"))
        assertEquals(ActivityStatus.Ready, ActivityStatus.ofOrder("READY"))
        assertEquals(ActivityStatus.OnTheWay, ActivityStatus.ofOrder("IN_DELIVERY"))
        assertEquals(ActivityStatus.Completed, ActivityStatus.ofOrder("DELIVERED"))
        assertEquals(ActivityStatus.Cancelled, ActivityStatus.ofOrder("CANCELLED"))
        assertEquals(ActivityStatus.Refunded, ActivityStatus.ofOrder("REFUNDED"))

        listOf("NEW", "ACCEPTED", "PREPARING", "READY", "IN_DELIVERY").forEach { raw ->
            assertTrue(raw, ActivityStatus.ofOrder(raw).isActive)
        }
        listOf("DELIVERED", "CANCELLED", "REFUNDED").forEach { raw ->
            assertFalse(raw, ActivityStatus.ofOrder(raw).isActive)
        }
    }

    @Test
    fun `booking and appointment statuses come from their own enums`() {
        assertEquals(ActivityStatus.Confirmed, ActivityStatus.ofBooking("CONFIRMED"))
        assertEquals(ActivityStatus.InProgress, ActivityStatus.ofBooking("ACTIVE"))
        assertEquals(ActivityStatus.Completed, ActivityStatus.ofBooking("COMPLETED"))
        assertEquals(ActivityStatus.Cancelled, ActivityStatus.ofBooking("CANCELLED"))

        assertEquals(ActivityStatus.Placed, ActivityStatus.ofAppointment("PENDING"))
        assertEquals(ActivityStatus.Confirmed, ActivityStatus.ofAppointment("CONFIRMED"))
        assertEquals(ActivityStatus.Completed, ActivityStatus.ofAppointment("COMPLETED"))
        assertEquals(ActivityStatus.Cancelled, ActivityStatus.ofAppointment("CANCELLED"))
        // Не пришёл — не отмена и не выполнено, но и не активная запись.
        assertEquals(ActivityStatus.Missed, ActivityStatus.ofAppointment("NO_SHOW"))
        assertFalse(ActivityStatus.Missed.isActive)
    }

    @Test
    fun `an active cinema ticket is valid, not in progress`() {
        // `ACTIVE` у билета значит «действителен», а не «идёт сейчас»: бейдж
        // «Выполняется» говорил бы про процесс, которого нет.
        assertEquals(ActivityStatus.Confirmed, ActivityStatus.ofTicket("ACTIVE"))
        assertEquals(ActivityStatus.Completed, ActivityStatus.ofTicket("USED"))
        assertEquals(ActivityStatus.Refunded, ActivityStatus.ofTicket("REFUNDED"))
        assertTrue(ActivityStatus.ofTicket("ACTIVE").isActive)
        assertFalse(ActivityStatus.ofTicket("USED").isActive)
    }

    @Test
    fun `status value is read regardless of case and separators`() {
        assertEquals(ActivityStatus.OnTheWay, ActivityStatus.ofOrder(" in-delivery "))
        assertEquals(ActivityStatus.Missed, ActivityStatus.ofAppointment("no_show"))
    }

    @Test
    fun `an unknown status stays active and does not hide the activity`() {
        // Набор статусов задаёт бэкенд. Новое значение не должно ни ронять
        // экран, ни прятать живую активность в историю.
        assertEquals(ActivityStatus.Unknown, ActivityStatus.ofOrder("ON_HOLD"))
        assertEquals(ActivityStatus.Unknown, ActivityStatus.ofOrder(null))
        assertEquals(ActivityStatus.Unknown, ActivityStatus.ofBooking(""))
        assertTrue(ActivityStatus.Unknown.isActive)
    }

    // --- Вид активности ---

    @Test
    fun `order kind follows the backend vertical`() {
        assertEquals(ActivityKind.FoodOrder, ActivityKind.ofOrderVertical("FOOD"))
        assertEquals(ActivityKind.ClothingOrder, ActivityKind.ofOrderVertical("CLOTHING"))
        assertEquals(ActivityKind.PharmacyOrder, ActivityKind.ofOrderVertical("PHARMACY"))
        assertEquals(ActivityKind.CinemaOrder, ActivityKind.ofOrderVertical("CINEMA"))
        assertEquals(ActivityKind.GamingOrder, ActivityKind.ofOrderVertical("GAMING"))
        assertEquals(ActivityKind.FoodOrder, ActivityKind.ofOrderVertical("food"))
    }

    @Test
    fun `an unknown vertical is still shown as an order`() {
        // Заказ есть, деньги списаны: спрятать его хуже, чем назвать общим
        // словом.
        assertEquals(ActivityKind.OtherOrder, ActivityKind.ofOrderVertical("BAKERY"))
        assertEquals(ActivityKind.OtherOrder, ActivityKind.ofOrderVertical(null))
    }

    // --- Ключ строки ---

    @Test
    fun `the row key includes the source`() {
        // У двух разных ручек бэкенда id могут совпасть, а ключ LazyColumn
        // обязан быть уникальным на весь список.
        val order = activity(id = "42", source = ActivitySource.Orders)
        val ticket = activity(id = "42", source = ActivitySource.CinemaTickets)

        assertEquals("Orders:42", order.key)
        assertEquals("CinemaTickets:42", ticket.key)
    }

    // --- Отбор и порядок ---

    @Test
    fun `the active tab shows the nearest first and history the most recent`() {
        val soon = activity(id = "soon", at = "2026-09-04T10:00:00Z")
        val later = activity(id = "later", at = "2026-09-09T10:00:00Z")
        val doneOld = activity(
            id = "old",
            at = "2026-08-01T10:00:00Z",
            status = ActivityStatus.Completed,
        )
        val doneNew = activity(
            id = "new",
            at = "2026-09-01T10:00:00Z",
            status = ActivityStatus.Cancelled,
        )
        val all = listOf(later, doneOld, soon, doneNew)

        assertEquals(
            listOf("soon", "later"),
            ActivityMerge.filter(all, ActivityFilter.Active).map(Activity::id),
        )
        assertEquals(
            listOf("new", "old"),
            ActivityMerge.filter(all, ActivityFilter.History).map(Activity::id),
        )
    }

    @Test
    fun `rows without a date go last, not first`() {
        val dated = activity(id = "dated", at = "2026-09-04T10:00:00Z")
        val undated = activity(id = "undated", at = null)
        val datedDone = activity(
            id = "dated",
            at = "2026-09-04T10:00:00Z",
            status = ActivityStatus.Completed,
        )
        val undatedDone = activity(id = "undated", at = null, status = ActivityStatus.Completed)

        assertEquals(
            listOf("dated", "undated"),
            ActivityMerge.filter(listOf(undated, dated), ActivityFilter.Active).map(Activity::id),
        )
        // И в истории тоже: наверху они заняли бы место того, что человек как
        // раз ищет.
        assertEquals(
            listOf("dated", "undated"),
            ActivityMerge
                .filter(listOf(undatedDone, datedDone), ActivityFilter.History)
                .map(Activity::id),
        )
    }

    @Test
    fun `rows with the same time keep a stable order`() {
        // Иначе строки переставлялись бы при каждой перезагрузке списка.
        val first = activity(id = "a", at = "2026-09-04T10:00:00Z")
        val second = activity(id = "b", at = "2026-09-04T10:00:00Z")

        assertEquals(
            ActivityMerge.filter(listOf(first, second), ActivityFilter.Active),
            ActivityMerge.filter(listOf(second, first), ActivityFilter.Active),
        )
    }

    @Test
    fun `appending pages deduplicates by key`() {
        // Активность с границы страниц приезжает дважды — в LazyColumn это
        // дубликат ключа и падение.
        val first = activity(id = "1")
        val second = activity(id = "2")

        val merged = ActivityMerge.append(listOf(first, second), listOf(second, activity(id = "3")))

        assertEquals(listOf("1", "2", "3"), merged.map(Activity::id))
    }

    @Test
    fun `same id from different sources is not a duplicate`() {
        val order = activity(id = "42", source = ActivitySource.Orders)
        val ticket = activity(id = "42", source = ActivitySource.CinemaTickets)

        assertEquals(2, ActivityMerge.append(listOf(order), listOf(ticket)).size)
    }

    // --- Частичный отказ ---

    @Test
    fun `a partial failure is not a screen failure`() {
        val feed = ActivityFeed(
            items = listOf(activity(id = "1")),
            failures = mapOf(ActivitySource.CinemaTickets to ApiFailure(ApiError.Timeout)),
            requested = ActivitySource.entries.toSet(),
        )

        assertFalse(feed.isTotalFailure)
        assertTrue(feed.isPartial)
    }

    @Test
    fun `nobody answered is a screen failure`() {
        // Так выглядит истёкшая сессия: 401 у всех пяти. Показывать здесь «вы
        // ещё ничего не заказывали» значит врать.
        val feed = ActivityFeed(
            failures = ActivitySource.entries.associateWith { ApiFailure(ApiError.Unauthorized) },
            requested = ActivitySource.entries.toSet(),
        )

        assertTrue(feed.isTotalFailure)
        assertFalse(feed.isPartial)
    }

    @Test
    fun `on load more nobody means nobody who was asked`() {
        // При догрузке спрашивают уже не всех: «отказали все» здесь — это все
        // двое, а не все пять.
        val asked = setOf(ActivitySource.Orders, ActivitySource.CinemaTickets)
        val feed = ActivityFeed(
            failures = asked.associateWith { ApiFailure(ApiError.Timeout) },
            requested = asked,
        )

        assertTrue(feed.isTotalFailure)
    }

    @Test
    fun `an empty answer from everyone is not a failure`() {
        val feed = ActivityFeed(requested = ActivitySource.entries.toSet())

        assertFalse(feed.isTotalFailure)
        assertFalse(feed.isPartial)
        assertFalse(feed.hasMore)
    }

    @Test
    fun `the first load asks every source`() {
        assertEquals(ActivitySource.entries.toSet(), ActivityFeed.FIRST_PAGES.keys)
        assertTrue(ActivityFeed.FIRST_PAGES.values.all { it == 0 })
    }

    private fun activity(
        id: String,
        source: ActivitySource = ActivitySource.Orders,
        status: ActivityStatus = ActivityStatus.Placed,
        at: String? = "2026-09-04T10:00:00Z",
    ) = Activity(
        id = id,
        source = source,
        kind = ActivityKind.FoodOrder,
        status = status,
        occurredAt = at?.let(Instant::parse),
    )
}
