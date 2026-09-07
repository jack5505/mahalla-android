package uz.mahalla.testutil

import kotlinx.coroutines.CompletableDeferred
import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.notifications.data.NotificationsRepository
import uz.mahalla.feature.notifications.domain.NotificationPage

/**
 * Центр уведомлений в памяти: ViewModel списка и бейджа проверяются без
 * MockWebServer. Ответ на каждую страницу задаётся отдельно — иначе догрузку
 * не отличить от повторной загрузки первой страницы.
 */
class FakeNotificationsRepository : NotificationsRepository {

    val pages: MutableMap<Int, ApiResult<NotificationPage>> = mutableMapOf()

    var defaultPage: ApiResult<NotificationPage> = ApiResult.Success(NotificationPage())

    var unreadCount: ApiResult<Int> = ApiResult.Success(0)

    var markAllRead: ApiResult<Unit> = ApiResult.Success(Unit)

    /** Отметка одного уведомления (issue #95). */
    var markRead: ApiResult<Unit> = ApiResult.Success(Unit)

    /** Пока не завершён, [markRead] не отвечает. `null` — отвечает сразу. */
    var markReadGate: CompletableDeferred<Unit>? = null

    val requestedPages = mutableListOf<Int>()

    /** Кого отмечали прочитанным — в порядке запросов. */
    val markReadIds = mutableListOf<String>()

    var unreadCalls: Int = 0
        private set

    var markAllReadCalls: Int = 0
        private set

    override suspend fun notifications(page: Int, size: Int): ApiResult<NotificationPage> {
        requestedPages += page
        return pages[page] ?: defaultPage
    }

    override suspend fun unreadCount(): ApiResult<Int> {
        unreadCalls++
        return unreadCount
    }

    override suspend fun markAllRead(): ApiResult<Unit> {
        markAllReadCalls++
        return markAllRead
    }

    override suspend fun markRead(id: String): ApiResult<Unit> {
        markReadIds += id
        // Ответ можно задержать: иначе не проверить, что происходит с
        // состоянием, пока отметка едет (issue #95).
        markReadGate?.await()
        return markRead
    }
}
