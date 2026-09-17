package uz.mahalla.feature.notifications.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uz.mahalla.feature.notifications.domain.NotificationCategory
import uz.mahalla.feature.notifications.domain.NotificationType

/**
 * Payload пуша → deep link (эпик 11).
 *
 * Это и есть тот разбор, ошибка в котором стоит дороже всего: по чужому id
 * экран заказа скажет «не найдено», и человек решит, что сломалось приложение,
 * а не что уведомление вело в никуда.
 */
class PushMessageTest {

    @Test
    fun `order push opens the order screen`() {
        val message = PushMessage.of(
            mapOf(
                "id" to "n-1",
                "type" to "ORDER_STATUS_UPDATED",
                "entityId" to "o-42",
                "title" to "Buyurtma yo'lda",
                "body" to "Kuryer chiqdi",
            ),
        )

        assertEquals(NotificationType.OrderStatusUpdated, message.type)
        assertEquals(NotificationCategory.Orders, message.category)
        assertEquals("mahalla://order/o-42", message.deepLink)
        assertEquals("n-1", message.tag)
    }

    @Test
    fun `subscription push opens the subscription screen without an entity`() {
        val message = PushMessage.of(mapOf("type" to "SUBSCRIPTION_EXPIRES"))

        assertEquals(NotificationCategory.Payments, message.category)
        assertEquals("mahalla://subscription", message.deepLink)
    }

    /**
     * Очередь и запись экрана по `entityId` не имеют: у очереди приходит id
     * талона, а экран требует `placeId`; у записи неизвестна вертикаль. Вести
     * такой пуш «примерно туда» нельзя — ведём в центр уведомлений, где он
     * точно лежит.
     */
    @Test
    fun `types without a screen of their own open the notification centre`() {
        listOf(
            "WALKIN_ACCEPTED",
            "APPOINTMENT_REMINDER",
            "PROMOTION_CREATED",
            "REVIEW_ADDED",
        ).forEach { type ->
            val message = PushMessage.of(mapOf("type" to type, "entityId" to "e-1"))
            assertEquals("mahalla://notifications", message.deepLink)
        }
    }

    @Test
    fun `unknown type still opens the notification centre`() {
        val message = PushMessage.of(mapOf("type" to "LOYALTY_POINTS_ADDED"))

        assertEquals(NotificationType.Unknown, message.type)
        assertEquals(NotificationCategory.Other, message.category)
        assertEquals("mahalla://notifications", message.deepLink)
    }

    /**
     * Заказ без `entityId` — это сломанный payload, а не повод открыть чужой
     * заказ: ведём в список.
     */
    @Test
    fun `order push without an entity id falls back to the notification centre`() {
        val message = PushMessage.of(mapOf("type" to "ORDER_PLACED", "entityId" to "   "))

        assertEquals("mahalla://notifications", message.deepLink)
    }

    @Test
    fun `empty payload does not crash and leads somewhere`() {
        val message = PushMessage.of(emptyMap())

        assertEquals(NotificationType.Unknown, message.type)
        assertNull(message.title)
        assertNull(message.body)
        assertEquals("mahalla://notifications", message.deepLink)
        // Ключа от сервера нет, `messageId` FCM в тесте тоже не передан —
        // ключом становится ссылка. В реальной доставке `messageId` есть
        // всегда: см. `fcm message id keeps unrelated pushes apart`.
        assertEquals("mahalla://notifications", message.tag)
    }

    /**
     * Без `id` от сервера двум разным по смыслу пушам (напоминание о записи и
     * подошедшая очередь) раньше доставалась одна и та же ссылка-заглушка
     * (`mahalla://notifications`), и второй пуш в шторке заменял первый —
     * человек терял уведомление молча. `RemoteMessage.messageId` свой у
     * каждой доставки и от бэкенда не зависит, поэтому именно он теперь
     * разводит такие пуши.
     */
    @Test
    fun `fcm message id keeps unrelated pushes apart`() {
        val reminder = PushMessage.of(
            data = mapOf("type" to "APPOINTMENT_REMINDER"),
            fcmMessageId = "msg-1",
        )
        val queueTurn = PushMessage.of(
            data = mapOf("type" to "WALKIN_ACCEPTED"),
            fcmMessageId = "msg-2",
        )

        assertEquals("mahalla://notifications", reminder.deepLink)
        assertEquals("mahalla://notifications", queueTurn.deepLink)
        assertEquals("msg-1", reminder.tag)
        assertEquals("msg-2", queueTurn.tag)
    }

    @Test
    fun `server id wins over the fcm message id`() {
        val message = PushMessage.of(
            data = mapOf("id" to "n-1", "type" to "PROMOTION_CREATED"),
            fcmMessageId = "msg-1",
        )

        assertEquals("n-1", message.tag)
    }

    /**
     * Бэкенд может прислать текст блоком `notification`, а не в `data` — тогда
     * он уже локализован сервером и дублируется не всегда.
     */
    @Test
    fun `notification block is used when data carries no text`() {
        val message = PushMessage.of(
            data = mapOf("type" to "ORDER_PLACED", "entityId" to "o-1"),
            fallbackTitle = "Buyurtma qabul qilindi",
            fallbackBody = "Osh Markazi tayyorlamoqda",
        )

        assertEquals("Buyurtma qabul qilindi", message.title)
        assertEquals("Osh Markazi tayyorlamoqda", message.body)
    }

    @Test
    fun `data text wins over the notification block`() {
        val message = PushMessage.of(
            data = mapOf("title" to "data", "body" to "data body"),
            fallbackTitle = "notification",
            fallbackBody = "notification body",
        )

        assertEquals("data", message.title)
        assertEquals("data body", message.body)
    }

    @Test
    fun `blank fields are treated as absent`() {
        val message = PushMessage.of(
            mapOf("id" to " ", "title" to "  ", "body" to "", "entityId" to " o-7 "),
        )

        assertNull(message.title)
        assertNull(message.body)
        assertEquals(null, message.notificationId)
        assertEquals("o-7", message.entityId)
    }
}
