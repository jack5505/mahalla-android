package uz.mahalla.feature.notifications.push

import uz.mahalla.feature.notifications.domain.NotificationCategory
import uz.mahalla.feature.notifications.domain.NotificationTarget
import uz.mahalla.feature.notifications.domain.NotificationType
import uz.mahalla.navigation.DeepLinks

/**
 * Разобранный payload пуша (эпик 11).
 *
 * **Схемы payload'а у бэкенда нет.** В `/v3/api-docs` (сверка 2026-09-09)
 * описан только центр уведомлений; что именно кладётся в `data` сообщения FCM,
 * контракт не говорит. Поэтому поля читаются по модели `NotificationResponse`
 * того же бэкенда — `id`, `type`, `entityId`, `title`, `body`: это его
 * собственные имена, а не выдуманные. Как только бэкенд начнёт слать пуши,
 * сверить и поправить здесь (`docs/API-CONTRACT.md`, раздел NotificationsApi).
 *
 * Разбор нарочно ничего не требует: пуш с пустым `data` — это всё равно пуш,
 * который человеку показали, и упасть на нём хуже, чем показать «Bildirishnoma»
 * со ссылкой на центр уведомлений.
 */
data class PushMessage(
    /** Id уведомления на сервере: он же ключ, по которому пуш заменяет себя. */
    val notificationId: String?,
    val type: NotificationType,
    val entityId: String?,
    val title: String?,
    val body: String?,
    /**
     * `RemoteMessage.messageId` — id самого сообщения FCM, не сервера. Он
     * гарантированно свой у каждой доставки (ставит Google, не бэкенд), и
     * нужен ровно на один случай: сервер не прислал `id` в `data` — см. [tag].
     */
    val fcmMessageId: String? = null,
) {

    /** Канал, в котором показывать. */
    val category: NotificationCategory get() = NotificationCategory.of(type)

    /**
     * Куда ведёт нажатие. Цели нет — ведём в центр уведомлений: там пуш точно
     * лежит, и нажатие всегда что-то открывает (см. [NotificationTarget]).
     */
    val deepLink: String
        get() = when (val target = NotificationTarget.of(type, entityId)) {
            is NotificationTarget.Order -> DeepLinks.order(target.orderId)
            NotificationTarget.Subscription -> DeepLinks.subscription()
            NotificationTarget.None -> DeepLinks.notifications()
        }

    /**
     * Ключ уведомления в системной шторке. Повторная доставка того же
     * уведомления обязана заменять предыдущее, а не множить копии.
     *
     * Без `id` от сервера ключом раньше служила ссылка — но у всех типов без
     * своего экрана (`WALKIN_*`, `APPOINTMENT_*`, акции, отзывы) ссылка одна и
     * та же (`mahalla://notifications`), и разные по смыслу пуши затирали бы
     * друг друга в шторке. [fcmMessageId] свой у каждой доставки и от бэкенда
     * не зависит — им ключ и становится; ссылка — только когда нет вообще
     * ничего (пустой `data` без блока `notification`, откуда `messageId` тоже
     * не взять в тесте).
     */
    val tag: String
        get() = notificationId?.takeIf { it.isNotBlank() }
            ?: fcmMessageId?.takeIf { it.isNotBlank() }
            ?: deepLink

    companion object {
        const val KEY_ID = "id"
        const val KEY_TYPE = "type"
        const val KEY_ENTITY_ID = "entityId"
        const val KEY_TITLE = "title"
        const val KEY_BODY = "body"

        /**
         * @param data `data` сообщения FCM.
         * @param fallbackTitle,fallbackBody блок `notification` сообщения, если
         * бэкенд прислал его вместо `data`. Он приходит уже локализованным
         * сервером, поэтому в `data` дублируется не всегда.
         * @param fcmMessageId `RemoteMessage.messageId` — см. [PushMessage.fcmMessageId].
         */
        fun of(
            data: Map<String, String>,
            fallbackTitle: String? = null,
            fallbackBody: String? = null,
            fcmMessageId: String? = null,
        ): PushMessage = PushMessage(
            notificationId = data[KEY_ID].orNullIfBlank(),
            type = NotificationType.fromServer(data[KEY_TYPE]),
            entityId = data[KEY_ENTITY_ID].orNullIfBlank(),
            title = data[KEY_TITLE].orNullIfBlank() ?: fallbackTitle.orNullIfBlank(),
            body = data[KEY_BODY].orNullIfBlank() ?: fallbackBody.orNullIfBlank(),
            fcmMessageId = fcmMessageId.orNullIfBlank(),
        )
    }
}

private fun String?.orNullIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
