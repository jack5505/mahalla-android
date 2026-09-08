package uz.mahalla.feature.notifications.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Разбор типа и цели перехода (issue #81). Цена ошибки здесь — экран заказа с
 * чужим id, то есть «не найдено» вместо уведомления, поэтому правила
 * закреплены тестом, а не только комментарием.
 */
class NotificationTargetTest {

    @Test
    fun `server enum values are recognised`() {
        assertEquals(
            NotificationType.OrderStatusUpdated,
            NotificationType.fromServer("ORDER_STATUS_UPDATED"),
        )
        assertEquals(NotificationType.WalkinRequest, NotificationType.fromServer("WALKIN_REQUEST"))
        assertEquals(
            NotificationType.SubscriptionExpires,
            NotificationType.fromServer("SUBSCRIPTION_EXPIRES"),
        )
        // Регистр и пробелы приходят от сериализатора бэкенда, а не от смысла.
        assertEquals(NotificationType.ReviewAdded, NotificationType.fromServer(" review_added "))
    }

    @Test
    fun `an unknown type is shown, not hidden`() {
        // Бэкенд заводит типы раньше, чем о них узнаёт приложение.
        assertEquals(NotificationType.Unknown, NotificationType.fromServer("LOYALTY_LEVEL_UP"))
        assertEquals(NotificationType.Unknown, NotificationType.fromServer(null))
    }

    @Test
    fun `order notifications lead to the order status screen`() {
        listOf(NotificationType.OrderPlaced, NotificationType.OrderStatusUpdated).forEach { type ->
            val notification = notification(type = type, entityId = "o-42")

            assertEquals(NotificationTarget.Order("o-42"), NotificationTarget.of(notification))
            assertTrue(notification.isActionable)
        }
    }

    @Test
    fun `types without a screen lead nowhere instead of guessing`() {
        // Очереди, брони, акций и подписок в приложении ещё нет, а у
        // `REVIEW_ADDED` из контракта не следует, отзыв это или заведение.
        listOf(
            NotificationType.WalkinRequest,
            NotificationType.AppointmentBooked,
            NotificationType.ReviewAdded,
            NotificationType.PromotionCreated,
            NotificationType.SubscriptionExpires,
            NotificationType.Unknown,
        ).forEach { type ->
            val notification = notification(type = type, entityId = "e-1")

            assertEquals(NotificationTarget.None, NotificationTarget.of(notification))
            assertFalse(notification.isActionable)
        }
    }

    @Test
    fun `an order notification without an entity id is not clickable`() {
        // Открывать нечего: id заказа сервер не прислал.
        listOf(null, "", "   ").forEach { entityId ->
            val notification = notification(
                type = NotificationType.OrderPlaced,
                entityId = entityId,
            )

            assertEquals(NotificationTarget.None, NotificationTarget.of(notification))
        }
    }

    @Test
    fun `unread is a reason to be clickable, read without a target is not`() {
        // Тап по непрочитанному гасит его (issue #95), поэтому строка
        // кликабельна даже там, где переходить некуда. А прочитанное без цели
        // нажатия не принимает: оно читалось бы как сломанное.
        val unread = notification(type = NotificationType.PromotionCreated, entityId = "e-1")
        assertTrue(unread.isTappable)
        assertFalse(unread.isActionable)

        assertFalse(unread.copy(isRead = true).isTappable)

        val readOrder = notification(type = NotificationType.OrderPlaced, entityId = "o-1")
            .copy(isRead = true)
        assertTrue(readOrder.isTappable)
    }

    private fun notification(type: NotificationType, entityId: String?) = AppNotification(
        id = "n-1",
        title = "Buyurtma",
        body = null,
        type = type,
        entityId = entityId,
        isRead = false,
        createdAt = Instant.parse("2026-08-31T09:12:00Z"),
    )
}
