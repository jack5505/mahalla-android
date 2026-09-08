package uz.mahalla.feature.notifications.data

import uz.mahalla.core.format.parseServerInstant
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.map
import uz.mahalla.data.network.ensureSuccess
import uz.mahalla.data.network.payload
import uz.mahalla.feature.notifications.domain.AppNotification
import uz.mahalla.feature.notifications.domain.NotificationPage
import uz.mahalla.feature.notifications.domain.NotificationType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Центр уведомлений (issue #81).
 *
 * Кэша нет намеренно: уведомление, прочитанное на другом устройстве, из Room
 * пришло бы непрочитанным, а бейдж «единица» при пустом списке хуже честной
 * ошибки.
 *
 * Интерфейс — ради тестов ViewModel: экран и бейдж проверяются без
 * MockWebServer.
 */
interface NotificationsRepository {

    suspend fun notifications(page: Int = 0, size: Int = PAGE_SIZE): ApiResult<NotificationPage>

    suspend fun unreadCount(): ApiResult<Int>

    /** Отметить прочитанным всё сразу — включая непрочитанное на других страницах. */
    suspend fun markAllRead(): ApiResult<Unit>

    /** Отметить прочитанным одно уведомление (issue #95). */
    suspend fun markRead(id: String): ApiResult<Unit>

    companion object {
        /** Столько же по умолчанию берёт и сам бэкенд. */
        const val PAGE_SIZE = 20
    }
}

@Singleton
class DefaultNotificationsRepository @Inject constructor(
    private val api: NotificationsApi,
) : NotificationsRepository {

    override suspend fun notifications(page: Int, size: Int): ApiResult<NotificationPage> =
        apiCall { api.notifications(page = page.coerceAtLeast(0), size = size).payload() }
            .map(NotificationPageDto::toDomain)

    /**
     * Счётчик приходит числом в `data`, а не объектом. Отрицательное значение —
     * ошибка сервера: бейдж «−3» ни о чём не говорит, а на решение «показывать
     * ли бейдж» влияет так же, как ноль. Верхнюю границу не режем здесь —
     * «99+» это дело экрана, а не контракта.
     */
    override suspend fun unreadCount(): ApiResult<Int> =
        apiCall { api.unreadCount().payload() }
            .map { count -> count.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt() }

    override suspend fun markAllRead(): ApiResult<Unit> =
        apiCall { api.markAllRead().ensureSuccess() }

    /**
     * `ensureSuccess()`, а не `payload()`: `data` у этой ручки пуст и при
     * успехе (`ApiResponseVoid`), и `payload()` превратил бы штатный ответ в
     * ошибку разбора.
     */
    override suspend fun markRead(id: String): ApiResult<Unit> =
        apiCall { api.markRead(id).ensureSuccess() }
}

/**
 * `hasMore` считается по `last`, а при его отсутствии — по `page`/`totalPages`.
 * Полного молчания сервера о страницах достаточно, чтобы остановиться: лучше
 * не показать хвост списка, чем зациклить догрузку одной и той же страницы
 * (то же правило, что у истории кошелька, issue #62).
 */
internal fun NotificationPageDto.toDomain(): NotificationPage {
    val pageIndex = page ?: 0
    val pages = totalPages
    return NotificationPage(
        items = content.mapNotNull(NotificationDto::toDomain),
        hasMore = when {
            last != null -> !last
            pages != null -> pageIndex + 1 < pages
            else -> false
        },
    )
}

/**
 * Разбор мягкий, как в каталоге (issue #53): запись без `id` отбрасывается —
 * в `LazyColumn` она стала бы дубликатом ключа, а отличить её от соседней всё
 * равно нечем.
 *
 * Уведомление без текста и без заголовка при этом остаётся: подпись под него
 * подставит экран. Пропасть оно не должно — бейдж считает сервер, и список,
 * который короче счётчика, читается как потеря.
 */
internal fun NotificationDto.toDomain(): AppNotification? {
    val notificationId = id?.takeIf { it.isNotBlank() } ?: return null
    return AppNotification(
        id = notificationId,
        title = title?.takeIf { it.isNotBlank() },
        body = body?.takeIf { it.isNotBlank() },
        type = NotificationType.fromServer(type),
        entityId = entityId?.takeIf { it.isNotBlank() },
        // Молчание сервера — «не прочитано»: спрятать непрочитанное хуже, чем
        // лишний раз подсветить прочитанное.
        isRead = isRead ?: read ?: false,
        createdAt = parseServerInstant(createdAt),
    )
}
