package uz.mahalla.feature.notifications.domain

import java.time.Instant

/**
 * Тип уведомления (`Notification.type` бэкенда, issue #81).
 *
 * Набор снят со схемы стенда (`/v3/api-docs`). [Unknown] обязателен: бэкенд
 * заводит новые типы раньше, чем приложение о них узнаёт, — и уведомление
 * незнакомого типа всё равно нужно показать в списке, а не спрятать.
 */
enum class NotificationType {
    WalkinRequest,
    WalkinAccepted,
    WalkinDeclined,
    WalkinCounter,
    WalkinComplete,
    AppointmentBooked,
    AppointmentConfirmed,
    AppointmentReminder,
    OrderPlaced,
    OrderStatusUpdated,
    ReviewAdded,
    PromotionCreated,
    SubscriptionExpires,
    Unknown,
    ;

    companion object {
        fun fromServer(value: String?): NotificationType = when (value?.trim()?.uppercase()) {
            "WALKIN_REQUEST" -> WalkinRequest
            "WALKIN_ACCEPTED" -> WalkinAccepted
            "WALKIN_DECLINED" -> WalkinDeclined
            "WALKIN_COUNTER" -> WalkinCounter
            "WALKIN_COMPLETE" -> WalkinComplete
            "APPOINTMENT_BOOKED" -> AppointmentBooked
            "APPOINTMENT_CONFIRMED" -> AppointmentConfirmed
            "APPOINTMENT_REMINDER" -> AppointmentReminder
            "ORDER_PLACED" -> OrderPlaced
            "ORDER_STATUS_UPDATED" -> OrderStatusUpdated
            "REVIEW_ADDED" -> ReviewAdded
            "PROMOTION_CREATED" -> PromotionCreated
            "SUBSCRIPTION_EXPIRES" -> SubscriptionExpires
            else -> Unknown
        }
    }
}

/**
 * Уведомление из центра уведомлений (`GET notifications`).
 *
 * @param title заголовок и [body] текст пишет бэкенд — на языке, который
 * выбрал он сам. Своих строк под типы уведомлений в приложении нет намеренно:
 * список типов открытый, и незнакомый тип остался бы без текста вовсе.
 * @param entityId сущность, о которой уведомление. Что именно это за id,
 * зависит от [type] — разбирает [NotificationTarget.of].
 */
data class AppNotification(
    val id: String,
    val title: String?,
    val body: String?,
    val type: NotificationType,
    val entityId: String?,
    val isRead: Boolean,
    val createdAt: Instant?,
)

/** Страница списка. `hasMore` считает репозиторий по ответу сервера. */
data class NotificationPage(
    val items: List<AppNotification> = emptyList(),
    val hasMore: Boolean = false,
)
