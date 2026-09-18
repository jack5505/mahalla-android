package uz.mahalla.feature.notifications.domain

/**
 * Куда ведёт уведомление (issue #81, эпик 11).
 *
 * Правило одно: открывать экран можно только там, где известно, **чем именно**
 * является `entityId`. Ошибка здесь стоит дорого — по чужому id экран заказа
 * покажет «не найдено», и человек решит, что сломалось приложение, а не что
 * уведомление вело в никуда.
 *
 * Поэтому [None] — не исключение, а обычный исход: у очереди (`WALKIN_*`)
 * экран требует `placeId`, а приходит id талона; у записи (`APPOINTMENT_*`)
 * список свой у мастеров и свой у врачей, а по типу уведомления не отличить,
 * чья это запись; у `REVIEW_ADDED` из контракта не следует, отзыв это или
 * заведение. Такое уведомление остаётся текстом в списке и не притворяется
 * кликабельным — а пуш по нему открывает центр уведомлений (эпик 11).
 */
sealed interface NotificationTarget {

    /** Статус заказа — `OrderStatusRoute(entityId)` вертикали «Еда» (эпик 5). */
    data class Order(val orderId: String) : NotificationTarget

    /**
     * Подписка — `SubscriptionRoute` (issue #103). Аргументов у экрана нет, и
     * это как раз то, что делает цель безопасной: `entityId` разбирать не
     * нужно, а «подписка заканчивается» ведёт ровно туда, где её продлевают.
     */
    data object Subscription : NotificationTarget

    /** Открывать нечего: список и есть конечный экран. */
    data object None : NotificationTarget

    companion object {
        /**
         * Чистая функция: разбор цели проверяется без Android и без навигации.
         * Незнакомый тип даёт [None] — уронить экран список уведомлений не
         * может ни при каком ответе сервера.
         */
        fun of(notification: AppNotification): NotificationTarget =
            of(notification.type, notification.entityId)

        /**
         * Тот же разбор для пуша (эпик 11): в payload'е приезжают тип и
         * `entityId`, а целого [AppNotification] там нет — его собирать не из
         * чего, и собранный наполовину он соврал бы про `isRead`.
         *
         * Пустой `entityId` для целей, которым он нужен, даёт [None].
         */
        fun of(type: NotificationType, entityId: String?): NotificationTarget {
            if (type == NotificationType.SubscriptionExpires) return Subscription
            val id = entityId?.trim()?.takeIf { it.isNotEmpty() } ?: return None
            return when (type) {
                NotificationType.OrderPlaced,
                NotificationType.OrderStatusUpdated,
                -> Order(id)

                else -> None
            }
        }
    }
}

/** Уведомление, по которому есть куда перейти. */
val AppNotification.isActionable: Boolean
    get() = NotificationTarget.of(this) != NotificationTarget.None

/**
 * По уведомлению есть что сделать, то есть строка списка кликабельна: либо
 * перейти на экран ([isActionable]), либо хотя бы погасить непрочитанное
 * (issue #95).
 *
 * Прочитанное уведомление без цели кликабельным не притворяется: нажатие без
 * последствий читается как сломанный экран — то же правило, что было в
 * issue #81, только теперь «последствие» есть и у уведомления, которое никуда
 * не ведёт.
 */
val AppNotification.isTappable: Boolean
    get() = isActionable || !isRead
