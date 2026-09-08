package uz.mahalla.feature.gaming.data

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
import uz.mahalla.feature.gaming.domain.GamingBookingDraft
import uz.mahalla.feature.gaming.domain.GamingBookingStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Игровые зоны (issue #98) на настоящем сетевом стеке ([NetworkFactory] +
 * [MockWebServer]): подмена Retrofit фейком не поймала бы ни ошибку в пути
 * запроса, ни несовпадение схемы JSON.
 *
 * Контракт снят со стенда 2026-09-04: `GET gaming/places/{id}/zones` (ручка
 * анонимна — `200` с `data: []` без токена), `POST gaming/bookings` и
 * `GET gaming/bookings/my` (обе `401 UNAUTHORIZED` без токена). Тело
 * `POST` в схеме перекрыто коллизией springdoc, поэтому его форма — решение
 * приложения (см. [GamingApi]), и эти тесты её и закрепляют.
 */
class GamingRepositoryTest {

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
    fun `zones come from the place path`() = runTest {
        server.enqueue(
            envelope(
                """[{"id":"z-1","placeId":"p-1","name":"PlayStation 5","zoneType":"CONSOLE",
                    "pricePerHour":35000,"totalSeats":4,"isAvailable":true}]""",
            ),
        )

        val result = repository().zones("p-1")

        val request = server.takeRequest()
        assertEquals("/gaming/places/p-1/zones", request.path)
        assertEquals("GET", request.method)

        val zone = (result as ApiResult.Success).data.single()
        assertEquals("z-1", zone.id)
        assertEquals("PlayStation 5", zone.name)
        assertEquals("CONSOLE", zone.zoneType)
        assertEquals(35_000L, zone.pricePerHour)
        assertEquals(4, zone.totalSeats)
        assertTrue(zone.isBookable)
    }

    @Test
    fun `both spellings of the availability flag are accepted`() = runTest {
        // Jackson сериализует `boolean isAvailable` то так, то так; ошибка
        // здесь увела бы в «закрыто» все зоны сразу.
        server.enqueue(
            envelope(
                """[{"id":"z-1","pricePerHour":1000,"available":true},
                    {"id":"z-2","pricePerHour":1000,"isAvailable":true}]""",
            ),
        )

        val zones = (repository().zones("p-1") as ApiResult.Success).data

        assertTrue(zones.all { it.isAvailable })
    }

    @Test
    fun `a zone the server said nothing about is shown as closed`() = runTest {
        server.enqueue(envelope("""[{"id":"z-1","pricePerHour":1000}]"""))

        val zone = (repository().zones("p-1") as ApiResult.Success).data.single()

        assertFalse(zone.isAvailable)
        assertFalse(zone.isBookable)
    }

    @Test
    fun `a zone without an id is dropped and the rest survive`() = runTest {
        // Забронировать её нечем, а в `LazyColumn` она была бы дубликатом
        // ключа. Остальные при этом остаются: пропасть из списка зона не
        // должна ни из-за пустого имени, ни из-за нулевой цены.
        server.enqueue(
            envelope(
                """[{"name":"no id"},{"id":"z-2","pricePerHour":0,"isAvailable":true},
                    {"id":"z-3","name":"","pricePerHour":5000,"isAvailable":true}]""",
            ),
        )

        val zones = (repository().zones("p-1") as ApiResult.Success).data

        assertEquals(listOf("z-2", "z-3"), zones.map { it.id })
        assertFalse(zones.first().isBookable)
        assertEquals("", zones.last().name)
    }

    @Test
    fun `an empty catalog of zones is not an error`() = runTest {
        // Ровно так стенд и отвечает сейчас — проверено curl'ом.
        server.enqueue(envelope("[]"))

        assertTrue((repository().zones("p-1") as ApiResult.Success).data.isEmpty())
    }

    @Test
    fun `booking sends the zone, the local start time and the hours`() = runTest {
        server.enqueue(
            envelope(
                """{"id":"b-1","zoneId":"z-1","placeId":"p-1",
                    "startTime":"2026-09-04T13:00:00","endTime":"2026-09-04T15:00:00",
                    "durationHours":2,"totalPrice":70000,"status":"CONFIRMED"}""",
            ),
        )

        val result = repository().book(
            GamingBookingDraft(
                zoneId = "z-1",
                startTime = Instant.parse("2026-09-04T13:00:00Z"),
                durationHours = 2,
            ),
            zoneName = "PlayStation 5",
        )

        val request = server.takeRequest()
        assertEquals("/gaming/bookings", request.path)
        assertEquals("POST", request.method)
        // Время уходит без зоны — так же, как бэкенд отдаёт его сам.
        assertEquals(
            """{"zoneId":"z-1","startTime":"2026-09-04T13:00:00","durationHours":2}""",
            request.body.readUtf8(),
        )

        val booking = (result as ApiResult.Success).data
        assertEquals("b-1", booking.id)
        assertEquals(GamingBookingStatus.Confirmed, booking.status)
        assertEquals(2, booking.durationHours)
        assertEquals(70_000L, booking.totalPrice)
        assertEquals(Instant.parse("2026-09-04T13:00:00Z"), booking.startTime)
        // Имя зоны в ответе не приходит: его знает только экран зон.
        assertEquals("PlayStation 5", booking.zoneName)
    }

    @Test
    fun `an unfilled draft does not reach the network`() = runTest {
        // 400 от сервера сказал бы то же самое, но платой были бы запрос и
        // молчание экрана на время его выполнения.
        val result = repository().book(GamingBookingDraft(zoneId = "z-1"))

        assertEquals(
            ApiError.Business(GamingRepository.INVALID_DRAFT_CODE),
            (result as ApiResult.Failure).error,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a slot that expired while the sheet was open does not reach the network`() = runTest {
        val result = repository().book(
            GamingBookingDraft(zoneId = "z-1", startTime = NOW.minusSeconds(60)),
        )

        assertEquals(
            ApiError.Business(GamingRepository.INVALID_DRAFT_CODE),
            (result as ApiResult.Failure).error,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a booking without an id is still a booking`() = runTest {
        // Отменять брони нечем (ручки нет), поэтому идентификатор приложению
        // некуда девать: показать подтверждение важнее.
        server.enqueue(envelope("""{"zoneId":"z-1","status":"CONFIRMED"}"""))

        val booking = (repository().book(draft(), zoneName = "VR") as ApiResult.Success).data

        assertEquals("", booking.id)
        assertEquals("VR", booking.zoneName)
        assertEquals(GamingBookingStatus.Confirmed, booking.status)
    }

    @Test
    fun `an unknown booking status does not break the answer`() = runTest {
        server.enqueue(envelope("""{"id":"b-1","status":"MOVED"}"""))

        val booking = (repository().book(draft()) as ApiResult.Success).data

        assertEquals(GamingBookingStatus.Unknown, booking.status)
        assertNull(booking.startTime)
    }

    @Test
    fun `a refusal of the booking keeps the text of the backend`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"ZONE_BUSY",
                       "message":"Bu vaqt band"}}""",
                ),
        )

        val failure = (repository().book(draft()) as ApiResult.Failure).failure

        assertEquals("Bu vaqt band", failure.serverMessage)
        assertEquals("ZONE_BUSY", failure.server?.code)
    }

    @Test
    fun `a 200 with success false is a failure, not an empty booking`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody("""{"success":false,"error":{"code":"ZONE_CLOSED"}}"""),
        )

        assertEquals(
            ApiError.Business("ZONE_CLOSED"),
            (repository().book(draft()) as ApiResult.Failure).error,
        )
    }

    @Test
    fun `my bookings ask for a page and read the envelope`() = runTest {
        server.enqueue(
            envelope(
                """{"content":[{"id":"b-1","zoneId":"z-1","startTime":"2026-09-04T13:00:00",
                    "durationHours":2,"totalPrice":70000,"status":"ACTIVE"}],
                    "page":0,"totalPages":2,"last":false}""",
            ),
        )

        val page = (repository().myBookings(page = 0, size = 20) as ApiResult.Success).data

        val request = server.takeRequest()
        assertEquals("/gaming/bookings/my?page=0&size=20", request.path)
        assertTrue(page.hasMore)
        assertEquals(GamingBookingStatus.Active, page.items.single().status)
        // Дата без зоны разбирается общим `parseServerInstant`.
        assertEquals(Instant.parse("2026-09-04T13:00:00Z"), page.items.single().startTime)
    }

    @Test
    fun `without last the page count decides, and without both the loading stops`() = runTest {
        server.enqueue(envelope("""{"content":[{"id":"b-1"}],"page":0,"totalPages":3}"""))
        assertTrue((repository().myBookings() as ApiResult.Success).data.hasMore)

        server.enqueue(envelope("""{"content":[{"id":"b-1"}]}"""))
        // Лучше не показать хвост, чем крутить одну страницу в цикле.
        assertFalse((repository().myBookings() as ApiResult.Success).data.hasMore)
    }

    @Test
    fun `a booking without an id is dropped from the list`() = runTest {
        server.enqueue(envelope("""{"content":[{"status":"ACTIVE"},{"id":"b-2"}],"last":true}"""))

        val page = (repository().myBookings() as ApiResult.Success).data

        assertEquals(listOf("b-2"), page.items.map { it.id })
    }

    @Test
    fun `a negative page number does not reach the backend`() = runTest {
        server.enqueue(envelope("""{"content":[],"last":true}"""))

        repository().myBookings(page = -3)

        assertEquals("/gaming/bookings/my?page=0&size=20", server.takeRequest().path)
    }

    @Test
    fun `an expired login is reported as unauthorized`() = runTest {
        // Именно так стенд отвечает без токена — проверено curl'ом.
        server.enqueue(MockResponse().setResponseCode(401))

        assertEquals(
            ApiError.Unauthorized,
            (repository().myBookings() as ApiResult.Failure).error,
        )
    }

    private fun draft() = GamingBookingDraft(
        zoneId = "z-1",
        startTime = NOW.plusSeconds(1_800),
        durationHours = 1,
    )

    private fun repository() = DefaultGamingRepository(
        api = NetworkFactory
            .retrofit(
                server.url("/").toString(),
                NetworkFactory.clientBuilder().build(),
                NetworkFactory.converterFactory(NetworkFactory.json()),
            )
            .create(GamingApi::class.java),
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
