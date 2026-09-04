package uz.mahalla.feature.notifications.domain

/**
 * Куда ведёт уведомление (issue #81).
 *
 * Правило одно: открывать экран можно только там, где известно, **чем именно**
 * является `entityId`. Ошибка здесь стоит дорого — по чужому id экран заказа
 * покажет «не найдено», и человек решит, что сломалось приложение, а не что
 * уведомление вело в никуда.
 *
 * Поэтому [None] — не исключение, а обычный исход: вертикалей «очередь»
 * (`WALKIN_*`) и «бронь» (`APPOINTMENT_*`) в приложении ещё нет, экранов акций
 * и подписок тоже, а у `REVIEW_ADDED` из контракта не следует, отзыв это или
 * заведение. Такое уведомление остаётся текстом в списке и не притворяется
 * кликабельным.
 */
sealed interface NotificationTarget {

    /** Статус заказа — `OrderStatusRoute(entityId)` вертикали «Еда» (эпик 5). */
    data class Order(val orderId: String) : NotificationTarget

    /** Открывать нечего: список и есть конечный экран. */
    data object None : NotificationTarget

    companion object {
        /**
         * Чистая функция: разбор цели проверяется без Android и без навигации.
         * Незнакомый тип и пустой `entityId` дают [None] — уронить экран
         * список уведомлений не может ни при каком ответе сервера.
         */
        fun of(notification: AppNotification): NotificationTarget {
            val entityId = notification.entityId?.trim()?.takeIf { it.isNotEmpty() }
                ?: return None
            return when (notification.type) {
                NotificationType.OrderPlaced,
                NotificationType.OrderStatusUpdated,
                -> Order(entityId)

                else -> None
            }
        }
    }
}

/** Уведомление, по которому есть куда перейти: строка списка кликабельна. */
val AppNotification.isActionable: Boolean
    get() = NotificationTarget.of(this) != NotificationTarget.None
