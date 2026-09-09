package uz.mahalla.feature.gaming.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Домен игровых зон (issue #98): разбор состояний, правила брони и слоты.
 *
 * Слоты считаются на клиенте, потому что расписания зоны бэкенд не отдаёт
 * вовсе — значит и проверять их некому, кроме этих тестов.
 */
class GamingTest {

    @Test
    fun `booking statuses of the backend are recognised`() {
        assertEquals(GamingBookingStatus.Confirmed, GamingBookingStatus.fromApi("CONFIRMED"))
        assertEquals(GamingBookingStatus.Active, GamingBookingStatus.fromApi("active"))
        assertEquals(GamingBookingStatus.Completed, GamingBookingStatus.fromApi(" COMPLETED "))
        assertEquals(GamingBookingStatus.Cancelled, GamingBookingStatus.fromApi("CANCELLED"))
    }

    @Test
    fun `an unknown status is not passed off as an active booking`() {
        // Новое состояние бэкенда не должно рисовать «предстоит»: это значило
        // бы придумать за сервер.
        assertEquals(GamingBookingStatus.Unknown, GamingBookingStatus.fromApi("MOVED"))
        assertEquals(GamingBookingStatus.Unknown, GamingBookingStatus.fromApi(null))
        assertEquals(GamingBookingStatus.Unknown, GamingBookingStatus.fromApi(" "))
        assertFalse(GamingBookingStatus.Unknown.isActive)
        assertTrue(GamingBookingStatus.Confirmed.isActive)
        assertTrue(GamingBookingStatus.Active.isActive)
        assertFalse(GamingBookingStatus.Completed.isActive)
    }

    @Test
    fun `a zone without a price cannot be booked`() {
        // Цена `0` — это молчание сервера, а не «бесплатно»: счёт неизвестного
        // размера предлагать нельзя.
        assertFalse(GamingZone(id = "z", placeId = "p", pricePerHour = 0, isAvailable = true).isBookable)
        assertFalse(
            GamingZone(id = "z", placeId = "p", pricePerHour = 30_000, isAvailable = false)
                .isBookable,
        )
        assertTrue(
            GamingZone(id = "z", placeId = "p", pricePerHour = 30_000, isAvailable = true)
                .isBookable,
        )
    }

    @Test
    fun `the total is the hourly price times the hours`() {
        val zone = GamingZone(id = "z", placeId = "p", pricePerHour = 35_000, isAvailable = true)

        assertEquals(105_000L, zone.totalPrice(3))
        // Отрицательные часы — не «возврат денег»: считаем нулём.
        assertEquals(0L, zone.totalPrice(-2))
    }

    @Test
    fun `a draft without a time is not ready to be sent`() {
        val errors = GamingBookingValidator.validate(
            GamingBookingDraft(zoneId = "z-1"),
            NOW,
        )

        assertEquals(listOf(GamingBookingError.TimeRequired), errors)
    }

    @Test
    fun `a slot that expired while the form was open is rejected`() {
        // Слоты считаются от «сейчас», а «сейчас» уходит вперёд, пока человек
        // выбирает длительность.
        val errors = GamingBookingValidator.validate(
            GamingBookingDraft(zoneId = "z-1", startTime = NOW.minusSeconds(60)),
            NOW,
        )

        assertEquals(listOf(GamingBookingError.TimeTooSoon), errors)
    }

    @Test
    fun `all the reasons come at once`() {
        val errors = GamingBookingValidator.validate(
            GamingBookingDraft(zoneId = "z-1", startTime = null, durationHours = 0),
            NOW,
        )

        assertEquals(
            listOf(
                GamingBookingError.TimeRequired,
                GamingBookingError.DurationOutOfRange(
                    GamingBookingDraft.MIN_HOURS,
                    GamingBookingDraft.MAX_HOURS,
                ),
            ),
            errors,
        )
    }

    @Test
    fun `a valid draft has no reasons`() {
        assertTrue(
            GamingBookingValidator.validate(
                GamingBookingDraft(zoneId = "z-1", startTime = NOW.plusSeconds(600)),
                NOW,
            ).isEmpty(),
        )
    }

    @Test
    fun `slots are half-hourly and start no earlier than now`() {
        // 12:07 по Ташкенту → первый слот 12:30, дальше сетка.
        val slots = GamingSlots.next(
            now = Instant.parse("2026-09-04T07:07:13Z"),
            zone = TASHKENT,
            count = 3,
        )

        assertEquals(
            listOf(
                Instant.parse("2026-09-04T07:30:00Z"),
                Instant.parse("2026-09-04T08:00:00Z"),
                Instant.parse("2026-09-04T08:30:00Z"),
            ),
            slots,
        )
    }

    @Test
    fun `a moment exactly on the grid stays the first slot`() {
        val slots = GamingSlots.next(
            now = Instant.parse("2026-09-04T07:00:00Z"),
            zone = TASHKENT,
            count = 1,
        )

        assertEquals(listOf(Instant.parse("2026-09-04T07:00:00Z")), slots)
    }

    @Test
    fun `seconds do not sneak a slot into the past`() {
        // 12:00:30 → 12:30, а не 12:00: округление вниз дало бы слот, который
        // сервер отверг бы как прошедший (грабля `DeliverySlots` эпика 5).
        val slots = GamingSlots.next(
            now = Instant.parse("2026-09-04T07:00:30Z"),
            zone = TASHKENT,
            count = 1,
        )

        assertEquals(listOf(Instant.parse("2026-09-04T07:30:00Z")), slots)
        assertTrue(slots.first().isAfter(Instant.parse("2026-09-04T07:00:30Z")))
    }

    @Test
    fun `an empty request gives an empty list instead of an endless one`() {
        assertTrue(GamingSlots.next(NOW, TASHKENT, count = 0).isEmpty())
        assertTrue(GamingSlots.next(NOW, TASHKENT, step = Duration.ZERO).isEmpty())
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-04T12:00:00Z")
        val TASHKENT: ZoneId = ZoneId.of("Asia/Tashkent")
    }
}
