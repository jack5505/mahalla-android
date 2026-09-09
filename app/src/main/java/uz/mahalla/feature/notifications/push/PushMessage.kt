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
     * уведомления обязана заменять предыдущее, а не множить копии, — а без
     * `id` от сервера заменять нечего, и тогда ключом служит ссылка: два
     * обновления статуса одного заказа человеку нужны как одно.
     */
    val tag: String get() = notificationId?.takeIf { it.isNotBlank() } ?: deepLink

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
         */
        fun of(
            data: Map<String, String>,
            fallbackTitle: String? = null,
            fallbackBody: String? = null,
        ): PushMessage = PushMessage(
            notificationId = data[KEY_ID].orNullIfBlank(),
            type = NotificationType.fromServer(data[KEY_TYPE]),
            entityId = data[KEY_ENTITY_ID].orNullIfBlank(),
            title = data[KEY_TITLE].orNullIfBlank() ?: fallbackTitle.orNullIfBlank(),
            body = data[KEY_BODY].orNullIfBlank() ?: fallbackBody.orNullIfBlank(),
        )
    }
}

private fun String?.orNullIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
