package uz.mahalla.core.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Фабрика событий без заведения (issue #226): `placeId` необязателен у
 * бэкенда, но там, где заведение есть, оно должно доехать, а не потеряться в
 * `metadata`.
 */
class AnalyticsQueuedEventsTest {

    @Test
    fun `a screen without a place carries no placeId`() {
        val event = AnalyticsQueuedEvents.screenOpened(AnalyticsScreens.WALLET)

        assertEquals("screen_view", event.name)
        assertNull(event.placeId)
        assertEquals(mapOf("screen" to "wallet"), event.metadata)
    }

    @Test
    fun `a search query is carried as is, not normalized`() {
        val event = AnalyticsQueuedEvents.searched("osh")

        assertEquals("search", event.name)
        assertNull(event.placeId)
        assertEquals(mapOf("query" to "osh"), event.metadata)
    }

    @Test
    fun `opening a vertical is a funnel step before a place is chosen`() {
        val event = AnalyticsQueuedEvents.verticalOpened("food")

        assertEquals("funnel.vertical_opened", event.name)
        assertNull(event.placeId)
        assertEquals(mapOf("vertical" to "food"), event.metadata)
    }

    @Test
    fun `a rejection carries the place and the server code, mirroring the success event`() {
        val orderRejected = AnalyticsQueuedEvents.orderRejected("p-1", "OUT_OF_STOCK")
        assertEquals("order_rejected", orderRejected.name)
        assertEquals("p-1", orderRejected.placeId)
        assertEquals(mapOf("code" to "OUT_OF_STOCK"), orderRejected.metadata)

        val bookingRejected = AnalyticsQueuedEvents.bookingRejected("p-2", "SLOT_TAKEN")
        assertEquals("booking_rejected", bookingRejected.name)
        assertEquals("p-2", bookingRejected.placeId)
        assertEquals(mapOf("code" to "SLOT_TAKEN"), bookingRejected.metadata)
    }
}
