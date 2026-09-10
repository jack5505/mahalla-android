package uz.mahalla.data.network.analytics

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
import uz.mahalla.core.analytics.AnalyticsEvents
import uz.mahalla.core.analytics.AnalyticsVertical
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.data.prefs.Session
import uz.mahalla.testutil.FakeSessionStore

/**
 * Отправка событий (issue #169) на настоящем сетевом стеке ([NetworkFactory] +
 * [MockWebServer]): подмена Retrofit фейком не поймала бы ни ошибку в пути, ни
 * несовпадение имён полей с `TrackEventRequest`.
 *
 * Контракт снят со стенда 2026-09-10: `POST analytics/track`,
 * `{placeId, eventType, lat, lng, metadata}`, ответ — конверт без нагрузки,
 * без токена `401`.
 */
class AnalyticsRepositoryTest {

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
    fun `an event goes to track as placeId and the server name of its type`() = runTest {
        server.enqueue(voidEnvelope())

        val result = repository().track(AnalyticsEvents.placeViewed("p-1"))

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("/analytics/track", request.path)
        assertEquals("POST", request.method)
        val body = Json.parseToJsonElement(request.body.readUtf8()) as JsonObject
        assertEquals("p-1", body.getValue("placeId").jsonPrimitive.content)
        // Именно `VIEW`: перечисление на бэкенде закрыто, имя из Kotlin он не
        // примет.
        assertEquals("VIEW", body.getValue("eventType").jsonPrimitive.content)
        // Координаты не заполняются — расчёт на `X-Geo-*` (допущение, issue
        // #226). Полей в `TrackEventRequest` сейчас нет, так что это защита от
        // того, чтобы их завели и заполнили молча, а не проверка текущего кода.
        assertFalse("lat" in body)
        assertFalse("lng" in body)
        // Пустая карта не отправляется: `metadata: {}` ничего не сообщает.
        assertFalse("metadata" in body)
    }

    @Test
    fun `a vertical rides in metadata because BOOK is one for all of them`() = runTest {
        server.enqueue(voidEnvelope())

        repository().track(AnalyticsEvents.booked("p-1", AnalyticsVertical.Queue))

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()) as JsonObject
        assertEquals("BOOK", body.getValue("eventType").jsonPrimitive.content)
        val metadata = body.getValue("metadata") as JsonObject
        assertEquals("queue", metadata.getValue("vertical").jsonPrimitive.content)
    }

    @Test
    fun `an empty response body is a success, not a broken schema`() = runTest {
        // `ApiResponseVoid`: при успехе `data` пуст, и это штатный ответ.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody("""{"success":true,"timestamp":"2026-09-10T05:23:40Z"}"""),
        )

        assertTrue(repository().track(AnalyticsEvents.placeViewed("p-1")) is ApiResult.Success)
    }

    @Test
    fun `without a session the request is not made at all`() = runTest {
        // Ручка не анонимна: проба 2026-09-10 отдаёт 401 даже с гео-заголовками.
        // Каталог же смотрят до входа, и тратить запрос на заведомый отказ
        // незачем — а `TokenAuthenticator` на нём ещё и полез бы за refresh.
        val result = repository(session = null).track(AnalyticsEvents.placeViewed("p-1"))

        // Свой машинный код, а не `Unauthorized`: в логе клиентский пропуск
        // должен быть отличим от настоящего 401 после провала refresh.
        assertEquals(
            ApiError.Business("ANALYTICS_NO_SESSION"),
            (result as ApiResult.Failure).error,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an event without a place is not sent either`() = runTest {
        // `placeId` обязателен — сервер ответил бы 400. Это ошибка вызывающего.
        val result = repository().track(AnalyticsEvents.placeViewed(" "))

        assertEquals(
            ApiError.Business("ANALYTICS_BLANK_PLACE_ID"),
            (result as ApiResult.Failure).error,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the actual 401 of the stand is reported as Unauthorized`() = runTest {
        // Фактический ответ стенда анониму с гео-заголовками.
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"UNAUTHORIZED",
                       "message":"Kirish uchun autentifikatsiya talab qilinadi"}}""",
                ),
        )

        val failure = (repository().track(AnalyticsEvents.placeViewed("p-1")) as ApiResult.Failure)
            .failure

        assertEquals(ApiError.Unauthorized, failure.error)
        assertEquals("Kirish uchun autentifikatsiya talab qilinadi", failure.serverMessage)
    }

    @Test
    fun `success false is a failure, not a sent event`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"VALIDATION_ERROR",
                       "message":"Noto'g'ri hodisa turi"}}""",
                ),
        )

        val failure = (repository().track(AnalyticsEvents.placeViewed("p-1")) as ApiResult.Failure)
            .failure

        assertEquals(ApiError.Business("VALIDATION_ERROR"), failure.error)
        assertEquals("Noto'g'ri hodisa turi", failure.serverMessage)
    }

    private fun repository(session: Session? = SESSION) = DefaultAnalyticsRepository(
        api = NetworkFactory
            .retrofit(
                server.url("/").toString(),
                NetworkFactory.clientBuilder().build(),
                NetworkFactory.converterFactory(NetworkFactory.json()),
            )
            .create(AnalyticsApi::class.java),
        sessionStore = FakeSessionStore(session),
    )

    private fun voidEnvelope(): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody("""{"success":true,"data":null}""")

    private companion object {
        val SESSION = Session(accessToken = "access", refreshToken = "refresh")
    }
}
