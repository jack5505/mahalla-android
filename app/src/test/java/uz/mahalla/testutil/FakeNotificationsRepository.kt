package uz.mahalla.testutil

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

    val requestedPages = mutableListOf<Int>()

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
}
