package uz.mahalla.feature.hospital.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Сетка времени приёма (issue #99).
 *
 * Проверять её нужно тестом, потому что в отличие от брони (issue #97) её
 * никто не подтверждает: свободных слотов больничный контроллер не отдаёт, и
 * единственная защита от «предложили время, которое уже прошло» — вот эти
 * правила.
 *
 * Все ожидания — в зоне заведения `Asia/Tashkent` (UTC+5), поэтому в тестах
 * фиксированный `Instant` и отдельный случай на границу суток.
 */
class DoctorScheduleTest {

    @Test
    fun `future day offers the whole grid`() {
        val times = DoctorSchedule.times(date = TOMORROW, now = NOW)

        assertEquals(DoctorSchedule.OPENS_AT, times.first())
        assertEquals(DoctorSchedule.LAST_START, times.last())
        // 08:00…19:30 с шагом 30 минут — 24 значения.
        assertEquals(24, times.size)
        assertTrue(times.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun `today drops the time that has already passed`() {
        // 09:00 UTC = 14:00 в Ташкенте.
        val times = DoctorSchedule.times(date = TODAY, now = NOW)

        assertEquals(LocalTime.of(14, 0), times.first())
        assertFalse(times.any { it.isBefore(LocalTime.of(14, 0)) })
        assertEquals(DoctorSchedule.LAST_START, times.last())
    }

    @Test
    fun `exact current time is still offered`() {
        // Ровно 14:00 в Ташкенте — это «сейчас», а не «прошло».
        assertTrue(DoctorSchedule.times(date = TODAY, now = NOW).contains(LocalTime.of(14, 0)))
    }

    @Test
    fun `evening leaves nothing for today`() {
        val evening = Instant.parse("2026-09-04T15:00:00Z") // 20:00 в Ташкенте

        assertTrue(DoctorSchedule.times(date = TODAY, now = evening).isEmpty())
        // …но завтрашний день предлагается целиком.
        assertEquals(24, DoctorSchedule.times(date = TOMORROW, now = evening).size)
    }

    @Test
    fun `past day is empty`() {
        assertTrue(DoctorSchedule.times(date = TODAY.minusDays(1), now = NOW).isEmpty())
    }

    /**
     * Зона заведения, а не устройства: в 21:00 UTC в Ташкенте уже следующий
     * день, и «сегодня» обязано означать его — иначе календарь начинался бы со
     * вчерашнего дня, на который записаться нельзя.
     */
    @Test
    fun `day boundary is counted in Tashkent`() {
        val lateUtc = Instant.parse("2026-09-04T21:00:00Z") // 02:00 5 сентября

        val dates = DoctorSchedule.dates(lateUtc)

        assertEquals(LocalDate.of(2026, 9, 5), dates.first())
        // Ночью приёма ещё не было — сетка на этот день целая.
        assertEquals(24, DoctorSchedule.times(date = dates.first(), now = lateUtc).size)
    }

    @Test
    fun `calendar starts today and covers two weeks`() {
        val dates = DoctorSchedule.dates(NOW)

        assertEquals(TODAY, dates.first())
        assertEquals(14, dates.size)
        assertEquals(TODAY.plusDays(13), dates.last())
    }

    private companion object {
        /** 09:00 UTC = 14:00 в Ташкенте, 4 сентября. */
        val NOW: Instant = Instant.parse("2026-09-04T09:00:00Z")
        val TODAY: LocalDate = LocalDate.of(2026, 9, 4)
        val TOMORROW: LocalDate = LocalDate.of(2026, 9, 5)
    }
}

/** Черновик записи: что можно отправить, а что нет. */
class DoctorAppointmentDraftTest {

    @Test
    fun `complete draft without complaint can be submitted`() {
        val draft = DoctorAppointmentDraft(
            doctorId = "d-1",
            date = LocalDate.of(2026, 9, 5),
            time = LocalTime.of(9, 0),
        )

        assertTrue(draft.canSubmit)
        // Жалоба необязательна и уходит отсутствующим полем.
        assertEquals(null, draft.complaintOrNull())
    }

    @Test
    fun `missing choice blocks submit`() {
        val full = DoctorAppointmentDraft(
            doctorId = "d-1",
            date = LocalDate.of(2026, 9, 5),
            time = LocalTime.of(9, 0),
        )

        assertFalse(full.copy(doctorId = null).canSubmit)
        assertFalse(full.copy(doctorId = "  ").canSubmit)
        assertFalse(full.copy(date = null).canSubmit)
        assertFalse(full.copy(time = null).canSubmit)
    }

    @Test
    fun `whitespace is not a complaint`() {
        val draft = DoctorAppointmentDraft(complaint = "   \n  ")

        assertEquals(null, draft.complaintOrNull())
        assertEquals(0, draft.trimmedComplaint.length)
    }

    @Test
    fun `complaint is trimmed but not cut`() {
        val draft = DoctorAppointmentDraft(complaint = "  tomoq og'riyapti  ")

        assertEquals("tomoq og'riyapti", draft.complaintOrNull())
    }

    /**
     * Лимит бэкенда — `@Size(max = 1000)`. Ровно на границе отправка ещё
     * разрешена: «не больше тысячи» это тысяча включительно.
     */
    @Test
    fun `too long complaint blocks submit at the backend limit`() {
        val base = DoctorAppointmentDraft(
            doctorId = "d-1",
            date = LocalDate.of(2026, 9, 5),
            time = LocalTime.of(9, 0),
        )

        val exact = base.copy(complaint = "a".repeat(DoctorAppointmentDraft.MAX_COMPLAINT_LENGTH))
        assertFalse(exact.isComplaintTooLong)
        assertTrue(exact.canSubmit)

        val over = base.copy(
            complaint = "a".repeat(DoctorAppointmentDraft.MAX_COMPLAINT_LENGTH + 1),
        )
        assertTrue(over.isComplaintTooLong)
        assertFalse(over.canSubmit)
    }
}
