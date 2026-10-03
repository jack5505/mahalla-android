package uz.mahalla.feature.activity.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uz.mahalla.core.format.DateTimeFormatters.AppZone
import uz.mahalla.feature.activity.domain.ActivityKind
import uz.mahalla.feature.activity.domain.ActivitySource
import uz.mahalla.feature.activity.domain.ActivityStatus
import uz.mahalla.feature.activity.domain.ActivityTarget
import uz.mahalla.feature.activity.domain.ActivityTimeKind
import uz.mahalla.feature.booking.data.AppointmentDto
import uz.mahalla.feature.cinema.data.CinemaTicketDto
import uz.mahalla.feature.food.data.OrderViewDto
import uz.mahalla.feature.gaming.data.GamingBookingDto
import java.time.Instant
import java.time.LocalDate

/**
 * Разбор пяти источников «моих активностей» (issue #73) — без прямых тестов
 * до issue #347. Покрыт только на уровне репозитория ([ActivityRepositoryTest]
 * через `MockWebServer`), а он не проверяет большинство мягких случаев (без
 * `id`, без даты, неизвестный статус) — сеть отвечает одним и тем же телом.
 */
class ActivityMappersTest {

    @Test
    fun `a food order without an id is dropped, not shown with a blank key`() {
        assertNull(order(id = null).toActivity())
        assertNull(order(id = "  ").toActivity())
    }

    @Test
    fun `a food order maps its vertical, sum and order number`() {
        val activity = order(
            id = "o-1",
            vertical = "FOOD",
            status = "PREPARING",
            totalAmount = 305_000,
            orderNumber = "A-42",
            placeId = "place-1",
        ).toActivity()

        requireNotNull(activity)
        assertEquals(ActivitySource.Orders, activity.source)
        assertEquals(ActivityKind.FoodOrder, activity.kind)
        assertEquals(ActivityStatus.InProgress, activity.status)
        assertEquals(3_050L, activity.amount)
        assertEquals("A-42", activity.note)
        assertEquals(ActivityTarget.FoodOrder("o-1"), activity.target)
        assertEquals("place-1", activity.placeId)
    }

    @Test
    fun `only a food order is clickable, other verticals open nowhere yet`() {
        val clothing = order(id = "o-2", vertical = "CLOTHING").toActivity()

        requireNotNull(clothing)
        assertEquals(ActivityKind.ClothingOrder, clothing.kind)
        assertEquals(ActivityTarget.None, clothing.target)
    }

    @Test
    fun `an unknown vertical becomes OtherOrder instead of disappearing`() {
        val activity = order(id = "o-3", vertical = "SCOOTERS").toActivity()

        assertEquals(ActivityKind.OtherOrder, requireNotNull(activity).kind)
    }

    @Test
    fun `a gaming booking without an id is dropped`() {
        assertNull(gamingBooking(id = null).toActivity())
    }

    @Test
    fun `a gaming booking sorts by its slot start, in Tashkent time, not creation`() {
        val activity = gamingBooking(
            id = "b-1",
            startTime = "2026-09-05T13:00:00",
            totalPrice = 100_000,
        ).toActivity()

        requireNotNull(activity)
        assertEquals(ActivityTimeKind.Event, activity.timeKind)
        assertEquals(
            LocalDate.of(2026, 9, 5).atTime(13, 0).atZone(AppZone).toInstant(),
            activity.occurredAt,
        )
        assertEquals(1_000L, activity.amount)
    }

    @Test
    fun `an appointment without an id is dropped`() {
        assertNull(appointment(id = null).toActivity(ActivitySource.DoctorAppointments))
    }

    @Test
    fun `an appointment with a known date is an upcoming event`() {
        val activity = appointment(
            id = "a-1",
            apptDate = "2026-09-10",
            startTime = "09:00",
            serviceName = "Soch olish",
        ).toActivity(ActivitySource.MasterAppointments)

        requireNotNull(activity)
        assertEquals(ActivityTimeKind.Event, activity.timeKind)
        assertEquals(
            LocalDate.of(2026, 9, 10).atTime(9, 0).atZone(AppZone).toInstant(),
            activity.occurredAt,
        )
        assertEquals(ActivityKind.MasterAppointment, activity.kind)
        assertEquals(ActivityTarget.MasterAppointment("a-1"), activity.target)
    }

    @Test
    fun `an appointment without a parseable date falls back to creation time, not null`() {
        val activity = appointment(id = "a-2", apptDate = null, createdAt = "2026-09-01T10:00:00Z")
            .toActivity(ActivitySource.DoctorAppointments)

        requireNotNull(activity)
        assertEquals(ActivityTimeKind.Recorded, activity.timeKind)
        assertEquals(Instant.parse("2026-09-01T10:00:00Z"), activity.occurredAt)
        assertEquals(ActivityKind.DoctorAppointment, activity.kind)
        assertEquals(ActivityTarget.DoctorAppointment("a-2"), activity.target)
    }

    @Test
    fun `a cinema ticket without an id is dropped`() {
        assertNull(ticket(id = null).toActivity())
    }

    @Test
    fun `a cinema ticket shows the seat as its note and opens its own card`() {
        val activity = ticket(id = "t-1", seatNumber = "B12", price = 45_000).toActivity()

        requireNotNull(activity)
        assertEquals("B12", activity.note)
        assertEquals(450L, activity.amount)
        assertEquals(ActivityTarget.CinemaTicket("t-1"), activity.target)
        assertEquals(ActivityTimeKind.Recorded, activity.timeKind)
    }

    private fun order(
        id: String?,
        vertical: String? = "FOOD",
        status: String? = "NEW",
        totalAmount: Long? = 0,
        orderNumber: String? = null,
        placeId: String? = null,
    ) = OrderViewDto(
        id = id,
        vertical = vertical,
        status = status,
        totalAmount = totalAmount,
        orderNumber = orderNumber,
        placeId = placeId,
        createdAt = "2026-09-01T10:00:00Z",
    )

    private fun gamingBooking(
        id: String?,
        startTime: String? = "2026-09-05T13:00:00",
        totalPrice: Long? = 0,
    ) = GamingBookingDto(id = id, startTime = startTime, totalPrice = totalPrice, status = "CONFIRMED")

    private fun appointment(
        id: String?,
        apptDate: String? = "2026-09-10",
        startTime: String? = "09:00",
        createdAt: String? = "2026-09-01T10:00:00Z",
        serviceName: String? = null,
    ) = AppointmentDto(
        id = id,
        apptDate = apptDate,
        startTime = startTime?.let { JsonPrimitive(it) },
        createdAt = createdAt,
        serviceName = serviceName,
        status = "CONFIRMED",
    )

    private fun ticket(
        id: String?,
        seatNumber: String? = null,
        price: Long? = 0,
    ) = CinemaTicketDto(
        id = id,
        seatNumber = seatNumber,
        price = price,
        status = "ACTIVE",
        createdAt = "2026-09-01T10:00:00Z",
    )
}
