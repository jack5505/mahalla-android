package uz.mahalla.feature.freelancer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.core.format.DateTimeFormatters
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Правила заказа у мастера (issue #107): разбор статусов и черновик формы.
 *
 * Форму нельзя проверить ни скриншотом, ни запросом, поэтому правила и живут
 * отдельным классом — здесь они и закрепляются.
 */
class FreelancerOrderTest {

    @Test
    fun `statuses of the contract are recognised`() {
        assertEquals(FreelancerOrderStatus.Pending, FreelancerOrderStatus.fromApi("PENDING"))
        assertEquals(FreelancerOrderStatus.Accepted, FreelancerOrderStatus.fromApi("ACCEPTED"))
        assertEquals(FreelancerOrderStatus.Rejected, FreelancerOrderStatus.fromApi("REJECTED"))
        assertEquals(FreelancerOrderStatus.Completed, FreelancerOrderStatus.fromApi("COMPLETED"))
        // Регистр и разделитель бэкенд не обещает.
        assertEquals(FreelancerOrderStatus.Accepted, FreelancerOrderStatus.fromApi(" accepted "))
    }

    @Test
    fun `unknown status is not final`() {
        // «Неизвестно, чем кончилось» — не «кончилось»: закрывать заказ по
        // незнакомому значению нельзя, иначе новый статус бэкенда молча
        // превратит живой заказ в архивный.
        val unknown = FreelancerOrderStatus.fromApi("IN_PROGRESS")

        assertEquals(FreelancerOrderStatus.Unknown, unknown)
        assertFalse(unknown.isFinal)
        assertEquals(FreelancerOrderStatus.Unknown, FreelancerOrderStatus.fromApi(null))
        assertEquals(FreelancerOrderStatus.Unknown, FreelancerOrderStatus.fromApi("   "))
    }

    @Test
    fun `only rejected and completed are final`() {
        assertFalse(FreelancerOrderStatus.Pending.isFinal)
        assertFalse(FreelancerOrderStatus.Accepted.isFinal)
        assertTrue(FreelancerOrderStatus.Rejected.isFinal)
        assertTrue(FreelancerOrderStatus.Completed.isFinal)
    }

    /** В контракте обязателен один `serviceId` — на нём и держится форма. */
    @Test
    fun `service is the only required field`() {
        assertFalse(FreelancerOrderDraft().canSubmit)
        assertFalse(FreelancerOrderDraft(serviceId = "  ").canSubmit)
        assertTrue(FreelancerOrderDraft(serviceId = "s-1").canSubmit)
    }

    @Test
    fun `too long address or comment blocks the order`() {
        val longAddress = FreelancerOrderDraft(
            serviceId = "s-1",
            address = "a".repeat(FreelancerOrderDraft.MAX_ADDRESS_LENGTH + 1),
        )
        val longComment = FreelancerOrderDraft(
            serviceId = "s-1",
            comment = "a".repeat(FreelancerOrderDraft.MAX_COMMENT_LENGTH + 1),
        )

        assertTrue(longAddress.isAddressTooLong)
        assertFalse(longAddress.canSubmit)
        assertTrue(longComment.isCommentTooLong)
        assertFalse(longComment.canSubmit)

        // Ровно по границе — ещё можно: `@Size(max = …)` включает её.
        val atLimit = FreelancerOrderDraft(
            serviceId = "s-1",
            address = "a".repeat(FreelancerOrderDraft.MAX_ADDRESS_LENGTH),
            comment = "a".repeat(FreelancerOrderDraft.MAX_COMMENT_LENGTH),
        )
        assertTrue(atLimit.canSubmit)
    }

    /** Пробелы — не текст: ни длиной, ни содержанием они не считаются. */
    @Test
    fun `blank fields go missing rather than empty`() {
        val draft = FreelancerOrderDraft(serviceId = "s-1", address = "   ", comment = "\n ")

        assertNull(draft.addressOrNull())
        assertNull(draft.commentOrNull())
        assertEquals("Chilonzor 7", FreelancerOrderDraft(address = " Chilonzor 7 ").addressOrNull())
    }

    /**
     * День сам по себе времени не назначает: без выбранного часа заказ идёт
     * «как можно скорее», то есть вообще без `scheduledAt`.
     */
    @Test
    fun `day without an hour is asap`() {
        val draft = FreelancerOrderDraft(serviceId = "s-1", date = LocalDate.of(2026, 9, 6))

        assertNull(draft.scheduledAt())
        assertNull(FreelancerOrderDraft(serviceId = "s-1", time = LocalTime.NOON).scheduledAt())
    }

    /** Выбранное время — это момент в зоне заведения, а не в зоне телефона. */
    @Test
    fun `scheduled moment is built in the app zone`() {
        val draft = FreelancerOrderDraft(
            serviceId = "s-1",
            date = LocalDate.of(2026, 9, 6),
            time = LocalTime.of(10, 30),
        )

        // 10:30 в Ташкенте (UTC+5) — это 05:30 UTC.
        assertEquals(Instant.parse("2026-09-06T05:30:00Z"), draft.scheduledAt())
        assertEquals(
            draft.scheduledAt(),
            draft.scheduledAt(DateTimeFormatters.AppZone),
        )
    }
}
