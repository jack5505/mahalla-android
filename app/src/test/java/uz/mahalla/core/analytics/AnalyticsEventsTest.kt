package uz.mahalla.core.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Имена, которые уходят на сервер (issue #169).
 *
 * Смысл теста — зафиксировать контракт: перечисление `eventType` у бэкенда
 * закрыто, чужое значение он не примет, а переименование варианта в Kotlin не
 * должно менять то, что уходит по сети.
 */
class AnalyticsEventsTest {

    @Test
    fun `the nine types keep the names the backend declared`() {
        // `TrackEventRequest.eventType` со стенда целиком (2026-09-10).
        val expected = listOf(
            "VIEW", "LIKE", "SAVE", "SHARE", "CALL", "NAVIGATE", "BOOK", "ORDER", "REVIEW",
        )

        assertEquals(expected, AnalyticsEventType.entries.map(AnalyticsEventType::serverName))
    }

    @Test
    fun `every vertical has its own name in metadata`() {
        val names = AnalyticsVertical.entries.map(AnalyticsVertical::serverName)

        // Дубликат склеил бы в панели две разные воронки в одну.
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.none(String::isBlank))
    }

    @Test
    fun `a funnel step names its vertical, a card action does not need to`() {
        val ordered = AnalyticsEvents.ordered("p-1", AnalyticsVertical.Food)
        assertEquals(AnalyticsEventType.Order, ordered.type)
        assertEquals(mapOf("vertical" to "food"), ordered.metadata)

        val booked = AnalyticsEvents.booked("p-1", AnalyticsVertical.Cinema)
        assertEquals(AnalyticsEventType.Book, booked.type)
        assertEquals(mapOf("vertical" to "cinema"), booked.metadata)

        // Действия карточки вертикали не знают — вертикаль там и не нужна.
        assertEquals(emptyMap<String, String>(), AnalyticsEvents.placeViewed("p-1").metadata)
        assertEquals(AnalyticsEventType.View, AnalyticsEvents.placeViewed("p-1").type)
        assertEquals(AnalyticsEventType.Call, AnalyticsEvents.placeCalled("p-1").type)
        assertEquals(AnalyticsEventType.Navigate, AnalyticsEvents.routeRequested("p-1").type)
        assertEquals(AnalyticsEventType.Review, AnalyticsEvents.reviewSubmitted("p-1").type)
    }
}
