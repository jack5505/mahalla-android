package uz.mahalla.feature.notifications.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.feature.notifications.domain.AppNotification
import uz.mahalla.feature.notifications.domain.NotificationType
import java.time.Instant

/**
 * Центр уведомлений (issue #81) на настоящем сетевом стеке ([NetworkFactory] +
 * [MockWebServer]): подмена Retrofit фейком не поймала бы ни ошибку в пути
 * запроса, ни несовпадение схемы JSON.
 *
 * Контракт снят со стенда: `GET notifications?page&size` → страница
 * `Notification`, `GET notifications/unread-count` → число,
 * `PUT notifications/read-all` и `PUT notifications/{id}/read` (issue #95) →
 * конверт без нагрузки.
 */
class NotificationsRepositoryTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `the list is requested by page and parsed out of the envelope`() = runTest {
        server.enqueue(
            envelope(
                """{"content":[{"id":"n-1","title":"Buyurtma qabul qilindi",
                   "body":"Osh Markazi tayyorlamoqda","type":"ORDER_STATUS_UPDATED",
                   "entityId":"o-42","isRead":false,
                   "createdAt":"2026-08-31T09:12:00"}],"page":0,"last":true}""",
            ),
        )

        val page = (repository().notifications() as ApiResult.Success).data

        assertEquals("/notifications?page=0&size=20", server.takeRequest().path)
        val notification = page.items.single()
        assertEquals("n-1", notification.id)
        assertEquals("Buyurtma qabul qilindi", notification.title)
        assertEquals("Osh Markazi tayyorlamoqda", notification.body)
        assertEquals(NotificationType.OrderStatusUpdated, notification.type)
        assertEquals("o-42", notification.entityId)
        assertFalse(notification.isRead)
        // Jackson на бэкенде отдаёт дату без зоны — иначе она пуста у всех.
        assertEquals(Instant.parse("2026-08-31T09:12:00Z"), notification.createdAt)
        assertFalse(page.hasMore)
    }

    @Test
    fun `read flag is accepted under both names the backend may use`() = runTest {
        server.enqueue(
            envelope(
                """{"content":[{"id":"n-1","read":true},{"id":"n-2","isRead":true},
                   {"id":"n-3"}],"last":true}""",
            ),
        )

        val page = (repository().notifications() as ApiResult.Success).data

        // Ошибка здесь покрасила бы непрочитанным весь список.
        assertEquals(listOf(true, true, false), page.items.map(AppNotification::isRead))
    }

    @Test
    fun `an entry without an id is dropped instead of breaking the list`() = runTest {
        server.enqueue(
            envelope("""{"content":[{"title":"?"},{"id":"n-2"}],"page":1,"totalPages":3}"""),
        )

        val page = (repository().notifications(page = 1) as ApiResult.Success).data

        assertEquals(listOf("n-2"), page.items.map(AppNotification::id))
        assertEquals("/notifications?page=1&size=20", server.takeRequest().path)
        // `last` не приехал — «есть ли ещё» считается по номеру страницы.
        assertTrue(page.hasMore)
    }

    @Test
    fun `an entry without a text stays in the list`() = runTest {
        // Бейдж считает сервер, и список короче счётчика читался бы как потеря.
        server.enqueue(envelope("""{"content":[{"id":"n-1","title":"","body":" "}],"last":true}"""))

        val notification = (repository().notifications() as ApiResult.Success).data.items.single()

        assertNull(notification.title)
        assertNull(notification.body)
        assertEquals(NotificationType.Unknown, notification.type)
    }

    @Test
    fun `silence about paging stops the load more loop`() = runTest {
        server.enqueue(envelope("""{"content":[{"id":"n-1"}]}"""))

        val page = (repository().notifications() as ApiResult.Success).data

        // Иначе экран догружал бы одну и ту же страницу до бесконечности.
        assertFalse(page.hasMore)
    }

    @Test
    fun `unread count comes as a bare number`() = runTest {
        server.enqueue(envelope("7"))

        val count = (repository().unreadCount() as ApiResult.Success).data

        assertEquals("/notifications/unread-count", server.takeRequest().path)
        assertEquals(7, count)
    }

    @Test
    fun `a negative count is read as nothing to show`() = runTest {
        server.enqueue(envelope("-3"))

        // Бейдж «−3» ни о чём не говорит и на решение «показывать ли» влияет
        // так же, как ноль.
        assertEquals(0, (repository().unreadCount() as ApiResult.Success).data)
    }

    @Test
    fun `mark all read is a put without a body`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody("""{"success":true}"""),
        )

        assertTrue(repository().markAllRead() is ApiResult.Success)

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/notifications/read-all", request.path)
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun `mark read is a put by id without a body`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody("""{"success":true}"""),
        )

        assertTrue(repository().markRead("n-1") is ApiResult.Success)

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/notifications/n-1/read", request.path)
        // Что делать с уведомлением, сказано путём: тела у ручки нет.
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun `mark read reports the server message`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"NOTIFICATION_NOT_FOUND",
                       "message":"Bildirishnoma topilmadi"}}""",
                ),
        )

        val failure = (repository().markRead("n-1") as ApiResult.Failure).failure

        assertEquals(ApiError.Business("NOTIFICATION_NOT_FOUND"), failure.error)
        assertEquals("Bildirishnoma topilmadi", failure.serverMessage)
    }

    @Test
    fun `mark read without a token is unauthorized`() = runTest {
        // Стенд отвечает ровно так: `401` c кодом `UNAUTHORIZED` (issue #95).
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"UNAUTHORIZED",
                       "message":"Kirish uchun autentifikatsiya talab qilinadi"}}""",
                ),
        )

        val failure = (repository().markRead("n-1") as ApiResult.Failure).failure

        assertEquals(ApiError.Unauthorized, failure.error)
        assertEquals("Kirish uchun autentifikatsiya talab qilinadi", failure.serverMessage)
    }

    @Test
    fun `success false is a failure, not an empty list`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"NOTIFICATIONS_UNAVAILABLE",
                       "message":"Bildirishnomalar vaqtincha ishlamayapti"}}""",
                ),
        )

        val failure = (repository().notifications() as ApiResult.Failure).failure

        assertEquals(ApiError.Business("NOTIFICATIONS_UNAVAILABLE"), failure.error)
        assertEquals("Bildirishnomalar vaqtincha ishlamayapti", failure.serverMessage)
    }

    @Test
    fun `expired token is reported as unauthorized`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        assertEquals(
            ApiError.Unauthorized,
            (repository().unreadCount() as ApiResult.Failure).error,
        )
    }

    private fun repository() = DefaultNotificationsRepository(
        NetworkFactory
            .retrofit(
                server.url("/").toString(),
                NetworkFactory.clientBuilder().build(),
                NetworkFactory.converterFactory(NetworkFactory.json()),
            )
            .create(NotificationsApi::class.java),
    )

    private fun envelope(data: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody("""{"success":true,"data":$data}""")
}
