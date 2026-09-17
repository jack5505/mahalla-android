package uz.mahalla.feature.hospital.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Свободные слоты записи к врачу (issue #181).
 *
 * До этой задачи слоты считались на клиенте, и тест проверял придуманную
 * сетку. Теперь их отдаёт сервер (`ApiResponseListString`, тот же вид, что у
 * слотов брони), и здесь проверяется только разбор ответа и то самое
 * правило, которое клиент по-прежнему добавляет сам: не предлагать
 * наступившее время.
 *
 * Все ожидания — в зоне заведения `Asia/Tashkent` (UTC+5), поэтому в тестах
 * фиксированный `Instant` и отдельный случай на границу суток.
 */
class DoctorSlotsTest {

    @Test
    fun `raw slots are parsed and sorted`() {
        val slots = DoctorSlots.available(
            raw = listOf("10:00", "09:00:00", "09:30"),
            date = TOMORROW,
            now = NOW,
        )

        assertEquals(
            listOf(LocalTime.of(9, 0), LocalTime.of(9, 30), LocalTime.of(10, 0)),
            slots.map(DoctorSlot::time),
        )
    }

    /** `startTime` уходит на сервер ровно той строкой, что от него и пришла. */
    @Test
    fun `the raw server string survives untouched`() {
        val slots = DoctorSlots.available(raw = listOf("09:00:00"), date = TOMORROW, now = NOW)

        assertEquals("09:00:00", slots.single().raw)
    }

    /** Один мусорный слот не должен ронять остальные. */
    @Test
    fun `an unparsable slot is dropped, the rest survive`() {
        val slots = DoctorSlots.available(
            raw = listOf("not-a-time", "09:00", ""),
            date = TOMORROW,
            now = NOW,
        )

        assertEquals(listOf(LocalTime.of(9, 0)), slots.map(DoctorSlot::time))
    }

    /** `"10:00"` и `"10:00:00"` — одно и то же время для человека и для ключа списка. */
    @Test
    fun `duplicate times by value are collapsed`() {
        val slots = DoctorSlots.available(
            raw = listOf("10:00", "10:00:00"),
            date = TOMORROW,
            now = NOW,
        )

        assertEquals(1, slots.size)
    }

    @Test
    fun `future day offers the whole server answer`() {
        val slots = DoctorSlots.available(
            raw = listOf("08:00", "19:30"),
            date = TOMORROW,
            now = NOW,
        )

        assertEquals(2, slots.size)
    }

    @Test
    fun `today drops the time that has already passed`() {
        // 09:00 UTC = 14:00 в Ташкенте.
        val slots = DoctorSlots.available(
            raw = listOf("09:00", "14:00", "19:00"),
            date = TODAY,
            now = NOW,
        )

        assertEquals(listOf(LocalTime.of(14, 0), LocalTime.of(19, 0)), slots.map(DoctorSlot::time))
    }

    @Test
    fun `exact current time is still offered`() {
        // Ровно 14:00 в Ташкенте — это «сейчас», а не «прошло».
        val slots = DoctorSlots.available(raw = listOf("14:00"), date = TODAY, now = NOW)

        assertTrue(slots.any { it.time == LocalTime.of(14, 0) })
    }

    @Test
    fun `evening leaves nothing for today even if the server offered it`() {
        val evening = Instant.parse("2026-09-04T15:00:00Z") // 20:00 в Ташкенте

        assertTrue(
            DoctorSlots.available(raw = listOf("09:00"), date = TODAY, now = evening).isEmpty(),
        )
    }

    @Test
    fun `an empty server answer is an empty day`() {
        assertTrue(DoctorSlots.available(raw = emptyList(), date = TODAY, now = NOW).isEmpty())
    }

    @Test
    fun `past day is empty even if the server offered slots`() {
        assertTrue(
            DoctorSlots.available(raw = listOf("09:00"), date = TODAY.minusDays(1), now = NOW)
                .isEmpty(),
        )
    }

    /**
     * Зона заведения, а не устройства: в 21:00 UTC в Ташкенте уже следующий
     * день, и «сегодня» обязано означать его — иначе календарь начинался бы со
     * вчерашнего дня, на который записаться нельзя.
     */
    @Test
    fun `day boundary is counted in Tashkent`() {
        val lateUtc = Instant.parse("2026-09-04T21:00:00Z") // 02:00 5 сентября

        val dates = DoctorSlots.dates(lateUtc)

        assertEquals(LocalDate.of(2026, 9, 5), dates.first())
    }

    @Test
    fun `calendar starts today and covers two weeks`() {
        val dates = DoctorSlots.dates(NOW)

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
            slot = SLOT,
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
            slot = SLOT,
        )

        assertFalse(full.copy(doctorId = null).canSubmit)
        assertFalse(full.copy(doctorId = "  ").canSubmit)
        assertFalse(full.copy(date = null).canSubmit)
        assertFalse(full.copy(slot = null).canSubmit)
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
            slot = SLOT,
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

    private companion object {
        val SLOT = DoctorSlot("09:00", LocalTime.of(9, 0))
    }
}
