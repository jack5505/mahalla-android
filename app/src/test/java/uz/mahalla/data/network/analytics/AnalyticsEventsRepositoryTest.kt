package uz.mahalla.data.network.analytics

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.data.network.NetworkFactory

/**
 * Отправка пачки на `POST analytics/events` (issue #226) на настоящем сетевом
 * стеке ([NetworkFactory] + [MockWebServer]) — контракт зафиксирован в
 * комментарии бэкенда 2026-09-19 (`jack5505/mahalla#217`).
 */
class AnalyticsEventsRepositoryTest {

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
    fun `a batch is sent with deviceId and each event keeps its own fields`() = runTest {
        server.enqueue(acceptedEnvelope(2))

        val result = repository().send(
            deviceId = "device-1",
            events = listOf(
                AnalyticsEventItemRequest(name = "screen_view", occurredAt = "2026-09-19T08:15:30Z"),
                AnalyticsEventItemRequest(
                    name = "checkout_rejected",
                    occurredAt = "2026-09-19T08:20:11Z",
                    placeId = "p-1",
                    metadata = mapOf("code" to "OUT_OF_STOCK"),
                ),
            ),
        )

        assertEquals(ApiResult.Success(2), result)
        val request = server.takeRequest()
        assertEquals("/analytics/events", request.path)
        assertEquals("POST", request.method)
        val body = Json.parseToJsonElement(request.body.readUtf8()) as JsonObject
        assertEquals("device-1", body.getValue("deviceId").jsonPrimitive.content)
        val events = body.getValue("events") as JsonArray
        assertEquals(2, events.size)
        val first = events[0] as JsonObject
        assertEquals("screen_view", first.getValue("name").jsonPrimitive.content)
        assertFalse("placeId" in first)
        val second = events[1] as JsonObject
        assertEquals("p-1", second.getValue("placeId").jsonPrimitive.content)
        assertEquals(
            "OUT_OF_STOCK",
            (second.getValue("metadata") as JsonObject).getValue("code").jsonPrimitive.content,
        )
    }

    @Test
    fun `accepted can be lower than sent, and that is still a success`() = runTest {
        // Событие вне окна `occurredAt` отбрасывается поштучно — issue #226.
        server.enqueue(acceptedEnvelope(1))

        val result = repository().send(
            deviceId = "device-1",
            events = listOf(
                AnalyticsEventItemRequest(name = "search", occurredAt = "2026-09-19T08:15:30Z"),
                AnalyticsEventItemRequest(name = "search", occurredAt = "2020-01-01T00:00:00Z"),
            ),
        )

        assertEquals(ApiResult.Success(1), result)
    }

    @Test
    fun `a whole batch rejection surfaces as an ordinary http error`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"VALIDATION_ERROR","message":"Bad request"}}""",
                ),
        )

        val result = repository().send(
            deviceId = "device-1",
            events = listOf(AnalyticsEventItemRequest(name = "search", occurredAt = "2026-09-19T08:15:30Z")),
        )

        assertTrue(result is ApiResult.Failure)
        assertEquals(ApiError.Http(400, "Client Error"), (result as ApiResult.Failure).error)
    }

    @Test
    fun `rate limiting is reported distinctly from other http errors`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))

        val result = repository().send(
            deviceId = "device-1",
            events = listOf(AnalyticsEventItemRequest(name = "search", occurredAt = "2026-09-19T08:15:30Z")),
        )

        assertEquals(ApiError.Http(429, "Client Error"), (result as ApiResult.Failure).error)
    }

    private fun repository() = DefaultAnalyticsEventsRepository(
        api = NetworkFactory
            .retrofit(
                server.url("/").toString(),
                NetworkFactory.clientBuilder().build(),
                NetworkFactory.converterFactory(NetworkFactory.json()),
            )
            .create(AnalyticsApi::class.java),
    )

    private fun acceptedEnvelope(accepted: Int): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody("""{"success":true,"data":{"accepted":$accepted}}""")
}
