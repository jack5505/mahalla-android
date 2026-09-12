package uz.mahalla.feature.notifications.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тип уведомления → канал (эпик 11).
 *
 * Канал определяет, увидит человек сообщение или нет: канал он выключает
 * отдельно от остальных. Ошибка маппинга поэтому не косметическая — акция,
 * попавшая в канал заказов, приходит тому, кто маркетинг выключил.
 */
class NotificationCategoryTest {

    @Test
    fun `order types go to the orders channel`() {
        assertEquals(
            NotificationCategory.Orders,
            NotificationCategory.of(NotificationType.OrderPlaced),
        )
        assertEquals(
            NotificationCategory.Orders,
            NotificationCategory.of(NotificationType.OrderStatusUpdated),
        )
    }

    @Test
    fun `walkin types go to the queue channel`() {
        listOf(
            NotificationType.WalkinRequest,
            NotificationType.WalkinAccepted,
            NotificationType.WalkinDeclined,
            NotificationType.WalkinCounter,
            NotificationType.WalkinComplete,
        ).forEach { type ->
            assertEquals(NotificationCategory.Queue, NotificationCategory.of(type))
        }
    }

    @Test
    fun `appointment types go to the bookings channel`() {
        listOf(
            NotificationType.AppointmentBooked,
            NotificationType.AppointmentConfirmed,
            NotificationType.AppointmentReminder,
        ).forEach { type ->
            assertEquals(NotificationCategory.Bookings, NotificationCategory.of(type))
        }
    }

    @Test
    fun `subscription goes to payments and promotion to marketing`() {
        assertEquals(
            NotificationCategory.Payments,
            NotificationCategory.of(NotificationType.SubscriptionExpires),
        )
        assertEquals(
            NotificationCategory.Marketing,
            NotificationCategory.of(NotificationType.PromotionCreated),
        )
    }

    /**
     * Главное свойство: незнакомый тип не теряется. Бэкенд заводит типы раньше,
     * чем приложение о них узнаёт, и без этой ветки пуш исчезал бы молча.
     */
    @Test
    fun `unknown and review fall back to the other channel`() {
        assertEquals(
            NotificationCategory.Other,
            NotificationCategory.of(NotificationType.Unknown),
        )
        assertEquals(
            NotificationCategory.Other,
            NotificationCategory.of(NotificationType.ReviewAdded),
        )
    }

    /** Канал есть у каждого типа: `when` без `else` этого не гарантирует. */
    @Test
    fun `every notification type maps to a channel`() {
        NotificationType.entries.forEach { type ->
            assertTrue(NotificationCategory.of(type) in NotificationCategory.entries)
        }
    }

    /**
     * Идентификаторы каналов уникальны и стабильны. Совпадение склеило бы две
     * категории в один системный канал, а переименование завело бы новый — со
     * значениями по умолчанию, то есть включённым тем, что человек выключил.
     */
    @Test
    fun `channel ids are unique and stable`() {
        val ids = NotificationCategory.entries.map(NotificationCategory::id)
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(
            listOf("orders", "queue", "bookings", "payments", "marketing", "other"),
            ids,
        )
    }
}
