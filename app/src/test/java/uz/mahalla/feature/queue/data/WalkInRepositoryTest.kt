package uz.mahalla.feature.queue.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.feature.queue.domain.WalkInRequest
import uz.mahalla.feature.queue.domain.WalkInStatus
import uz.mahalla.testutil.FakeWalkInTicketStore
import uz.mahalla.testutil.walkInTicket
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Очередь (issue #96) на настоящем сетевом стеке ([NetworkFactory] +
 * [MockWebServer]): подмена Retrofit фейком не поймала бы ни ошибку в пути
 * запроса, ни несовпадение схемы JSON.
 *
 * Контракт снят со стенда 2026-09-04: `POST walkin/send` с телом
 * `{placeId, userName, serviceName?}` → конверт со схемой `Response`,
 * `POST walkin/{id}/cancel` без тела → тот же конверт. Ручки **чтения**
 * талона у бэкенда нет — поэтому здесь нет и теста на неё.
 */
class WalkInRepositoryTest {

    private lateinit var server: MockWebServer
    private val store = FakeWalkInTicketStore()

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
    fun `taking a ticket sends the place, the name and the service`() = runTest {
        server.enqueue(
            envelope(
                """{"id":"t-1","placeId":"p-1","userName":"Jahongir","serviceName":"Soch olish",
                   "status":"ACCEPTED","queuePosition":3,"estimatedWaitMinutes":25,
                   "createdAt":"2026-09-04T09:00:00"}""",
            ),
        )

        val result = repository().take(
            WalkInRequest(placeId = "p-1", userName = "Jahongir", serviceName = "Soch olish"),
            placeName = "Barber House",
        )

        val request = server.takeRequest()
        assertEquals("/walkin/send", request.path)
        assertEquals("POST", request.method)
        assertEquals(
            """{"placeId":"p-1","userName":"Jahongir","serviceName":"Soch olish"}""",
            request.body.readUtf8(),
        )

        val ticket = (result as ApiResult.Success).data
        assertEquals("t-1", ticket.id)
        assertEquals(WalkInStatus.Accepted, ticket.status)
        assertEquals(3, ticket.queuePosition)
        assertEquals(25, ticket.estimatedWaitMinutes)
        // Название заведения в ответе не приходит: его знает только карточка.
        assertEquals("Barber House", ticket.placeName)
        assertEquals(NOW, ticket.receivedAt)
        assertEquals(Instant.parse("2026-09-04T09:00:00Z"), ticket.createdAt)
        // Талон запомнен локально: прочитать его у бэкенда потом будет нечем.
        assertEquals("t-1", store.active("p-1")?.id)
    }

    @Test
    fun `an empty service is not sent as null`() = runTest {
        server.enqueue(envelope("""{"id":"t-1","status":"PENDING"}"""))

        repository().take(
            WalkInRequest(placeId = "p-1", userName = "Jahongir", serviceName = "   "),
            placeName = "Barber House",
        )

        assertEquals(
            """{"placeId":"p-1","userName":"Jahongir"}""",
            server.takeRequest().body.readUtf8(),
        )
    }

    @Test
    fun `an unfilled request never reaches the network`() = runTest {
        val result = repository().take(
            WalkInRequest(placeId = "p-1", userName = " "),
            placeName = "Barber House",
        )

        // 400 от сервера сказал бы то же самое, но платой были бы запрос и
        // молчание экрана на время его выполнения.
        assertEquals(
            ApiError.Business(WalkInRepository.INVALID_REQUEST_CODE),
            (result as ApiResult.Failure).error,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a ticket without an id is a failure, not a silent success`() = runTest {
        server.enqueue(envelope("""{"status":"PENDING","queuePosition":2}"""))

        val result = repository().take(request(), placeName = "Barber House")

        // Отменить такой талон нечем: показать его как взятый значит запереть
        // человека в очереди без выхода.
        assertEquals(ApiError.Serialization, (result as ApiResult.Failure).error)
        assertNull(store.active("p-1"))
    }

    @Test
    fun `an unknown status arrives as unknown and does not break the parse`() = runTest {
        server.enqueue(envelope("""{"id":"t-1","status":"ON_THE_WAY"}"""))

        val ticket = (repository().take(request(), "Barber House") as ApiResult.Success).data

        assertEquals(WalkInStatus.Unknown, ticket.status)
    }

    @Test
    fun `counter time is read both as an object and as a string`() = runTest {
        // Из схемы стенда не следует, какой вид приедет: springdoc описывает
        // `LocalTime` объектом, Jackson с JavaTimeModule отдаёт строку.
        server.enqueue(
            envelope(
                """{"id":"t-1","status":"COUNTER_OFFERED",
                   "counterTime":{"hour":14,"minute":30,"second":0,"nano":0}}""",
            ),
        )
        server.enqueue(
            envelope("""{"id":"t-2","status":"COUNTER_OFFERED","counterTime":"15:45:00"}"""),
        )

        val repository = repository()
        val fromObject = (repository.take(request(), "Barber House") as ApiResult.Success).data
        val fromText = (repository.take(request(), "Barber House") as ApiResult.Success).data

        assertEquals(LocalTime.of(14, 30), fromObject.counterTime)
        assertEquals(LocalTime.of(15, 45), fromText.counterTime)
    }

    @Test
    fun `garbage instead of a position does not travel to the screen`() = runTest {
        server.enqueue(
            envelope(
                """{"id":"t-1","status":"WAITING","queuePosition":-4,
                   "estimatedWaitMinutes":-1,"counterTime":"25:61"}""",
            ),
        )

        val ticket = (repository().take(request(), "Barber House") as ApiResult.Success).data

        // «Минус четвёртый в очереди» — не позиция, а мусор.
        assertNull(ticket.queuePosition)
        assertNull(ticket.estimatedWaitMinutes)
        assertNull(ticket.counterTime)
    }

    @Test
    fun `cancelling goes to the ticket path and drops the local ticket`() = runTest {
        server.enqueue(envelope("""{"id":"t-1","status":"CANCELLED"}"""))
        val ticket = walkIn()
        store.put(ticket)

        val result = repository().cancel(ticket)

        val request = server.takeRequest()
        assertEquals("/walkin/t-1/cancel", request.path)
        assertEquals("POST", request.method)
        assertEquals(0, request.body.size)
        assertEquals(WalkInStatus.Cancelled, (result as ApiResult.Success).data.status)
        // Талон больше не живой — запись в это же заведение снова доступна.
        assertNull(store.active("p-1"))
    }

    @Test
    fun `a cancel answered without a body still counts as cancelled`() = runTest {
        // Ответ описан коллизией схем, и неудачный разбор не должен превращать
        // удавшуюся отмену в «отменить не удалось».
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody("""{"success":true}"""),
        )

        val result = repository().cancel(walkIn())

        assertEquals(WalkInStatus.Cancelled, (result as ApiResult.Success).data.status)
        assertEquals(NOW, result.data.receivedAt)
    }

    @Test
    fun `a refused cancel keeps the ticket and reports the server text`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"WALKIN_ALREADY_STARTED",
                       "message":"Xizmat allaqachon boshlangan"}}""",
                ),
        )
        val ticket = walkIn(status = WalkInStatus.Waiting)
        store.put(ticket)

        val failure = (repository().cancel(ticket) as ApiResult.Failure).failure

        assertEquals(409, (failure.error as ApiError.Http).code)
        assertEquals("WALKIN_ALREADY_STARTED", failure.server?.code)
        // Причину показывает экран — текстом сервера (issue #34).
        assertEquals("Xizmat allaqachon boshlangan", failure.serverMessage)
        assertEquals("t-1", store.active("p-1")?.id)
    }

    @Test
    fun `a 2xx with success false is a failure`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"PLACE_CLOSED",
                       "message":"Muassasa hozir yopiq"}}""",
                ),
        )

        val failure = (repository().take(request(), "Barber House") as ApiResult.Failure).failure

        assertEquals(ApiError.Business("PLACE_CLOSED"), failure.error)
        assertEquals("Muassasa hozir yopiq", failure.serverMessage)
    }

    @Test
    fun `an expired login is reported as unauthorized`() = runTest {
        // Именно так стенд отвечает без токена — проверено curl'ом.
        server.enqueue(MockResponse().setResponseCode(401))

        assertEquals(
            ApiError.Unauthorized,
            (repository().take(request(), "Barber House") as ApiResult.Failure).error,
        )
    }

    @Test
    fun `the active ticket comes from the local store, not from the network`() = runTest {
        // Читать состояние талона у бэкенда нечем: ни `walkin/my`, ни
        // `walkin/{id}` в контракте нет.
        store.put(walkIn(status = WalkInStatus.Waiting))

        val ticket = repository().activeTicket("p-1")

        assertEquals("t-1", ticket?.id)
        assertFalse(WalkInStatus.Cancelled == ticket?.status)
        assertEquals(0, server.requestCount)
    }

    private fun request() = WalkInRequest(placeId = "p-1", userName = "Jahongir")

    private fun walkIn(status: WalkInStatus = WalkInStatus.Pending) =
        walkInTicket(status = status)

    private fun repository() = DefaultWalkInRepository(
        api = NetworkFactory
            .retrofit(
                server.url("/").toString(),
                NetworkFactory.clientBuilder().build(),
                NetworkFactory.converterFactory(NetworkFactory.json()),
            )
            .create(WalkInApi::class.java),
        store = store,
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
    )

    private fun envelope(data: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody("""{"success":true,"data":$data}""")

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-04T12:00:00Z")
    }
}
