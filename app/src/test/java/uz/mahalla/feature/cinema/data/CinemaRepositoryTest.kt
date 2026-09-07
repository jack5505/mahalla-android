package uz.mahalla.feature.cinema.data

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
import uz.mahalla.feature.cinema.domain.CinemaTicket
import uz.mahalla.feature.cinema.domain.CinemaTicketStatus
import uz.mahalla.feature.cinema.domain.SeatChoice
import java.time.LocalDate
import java.time.LocalTime

/**
 * Кино (issue #106) на настоящем сетевом стеке ([NetworkFactory] +
 * [MockWebServer]): подмена Retrofit фейком не поймала бы ни ошибку в пути
 * запроса, ни несовпадение схемы JSON.
 *
 * Контракт снят со стенда 2026-09-04. Афиша и расписание анонимны (`200` без
 * токена), покупка, свои билеты и возврат требуют Bearer (`401`).
 *
 * **Тело `POST cinema/sessions/{id}/buy` подтвердить живым запросом нельзя** —
 * в схеме оно `object` с `additionalProperties: string`, а `401` приходит до
 * валидации. Тест закрепляет то, что приложение отправляет **сейчас**, чтобы
 * правка после проверки под токеном была видна одной строкой.
 */
class CinemaRepositoryTest {

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

    // --- Афиша ---

    @Test
    fun `movies are parsed out of the envelope`() = runTest {
        server.enqueue(
            envelope(
                """[{"id":"m-1","placeId":"$PLACE","title":"Dune","titleUz":"Qum sayyorasi",
                   "genre":"Fantastika","durationMinutes":155,"releaseDate":"2026-08-20",
                   "posterUrl":"https://cdn/dune.jpg","rating":"16+","isActive":true}]""",
            ),
        )

        val movies = (repository().movies() as ApiResult.Success).data

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        // Ни одного параметра: афиша общая на всю платформу.
        assertEquals("/cinema/movies", request.path)
        assertEquals(1, movies.size)
        val movie = movies.first()
        assertEquals("Dune", movie.title)
        assertEquals("Qum sayyorasi", movie.titleUz)
        assertEquals(155, movie.durationMinutes)
        assertEquals(LocalDate.of(2026, 8, 20), movie.releaseDate)
        assertEquals("16+", movie.ageRating)
        assertEquals(PLACE, movie.placeId)
    }

    /** Фильм без `id` не сопоставить с сеансом — он выпадает, остальные живут. */
    @Test
    fun `movie without id is dropped and the rest survives`() = runTest {
        server.enqueue(
            envelope(
                """[{"title":"Ismsiz"},{"id":"  "},
                   {"id":"m-2","title":"Ilhom","durationMinutes":0,"releaseDate":"kecha"}]""",
            ),
        )

        val movies = (repository().movies() as ApiResult.Success).data

        assertEquals(1, movies.size)
        assertEquals("m-2", movies.first().id)
        // Мусор в полях фильм не прячет.
        assertNull(movies.first().durationMinutes)
        assertNull(movies.first().releaseDate)
    }

    /** `isActive` приезжает и под именем `active` — иначе прокат пуст у всех. */
    @Test
    fun `both spellings of the active flag are accepted`() = runTest {
        server.enqueue(
            envelope(
                """[{"id":"m-1","title":"A","active":false},
                   {"id":"m-2","title":"B","isActive":false},
                   {"id":"m-3","title":"C"}]""",
            ),
        )

        val movies = (repository().movies() as ApiResult.Success).data

        assertFalse(movies[0].isActive)
        assertFalse(movies[1].isActive)
        // Молчание сервера — «идёт».
        assertTrue(movies[2].isActive)
    }

    // --- Расписание ---

    @Test
    fun `schedule is requested by place and day`() = runTest {
        server.enqueue(
            envelope(
                """[{"id":"s-1","placeId":"$PLACE","movieId":"$MOVIE","hallName":"1-zal",
                   "sessionDate":"2026-09-05","startTime":"18:30:00","endTime":"21:05:00",
                   "ticketPrice":45000,"totalSeats":120,"availableSeats":12}]""",
            ),
        )

        val sessions = (
            repository().schedule(PLACE, LocalDate.of(2026, 9, 5)) as ApiResult.Success
            ).data

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/cinema/places/$PLACE/schedule?date=2026-09-05", request.path)
        assertEquals(1, sessions.size)
        val session = sessions.first()
        assertEquals(LocalDate.of(2026, 9, 5), session.date)
        assertEquals(LocalTime.of(18, 30), session.startTime)
        assertEquals(LocalTime.of(21, 5), session.endTime)
        assertEquals(45_000L, session.priceSum)
        assertEquals(12, session.availableSeats)
        assertEquals("1-zal", session.hallName)
    }

    /**
     * `LocalTime` приезжает и объектом (так его описывает springdoc), и
     * строкой (так его отдаёт Jackson) — ошибка в типе уронила бы разбор
     * всего расписания.
     */
    @Test
    fun `session time is parsed as an object too`() = runTest {
        server.enqueue(
            envelope(
                """[{"id":"s-1","startTime":{"hour":9,"minute":15,"second":0,"nano":0}}]""",
            ),
        )

        val sessions = (
            repository().schedule(PLACE, LocalDate.of(2026, 9, 5)) as ApiResult.Success
            ).data

        assertEquals(LocalTime.of(9, 15), sessions.single().startTime)
    }

    /** Сеанс без `id` купить нечем; мусорный остаток мест — это «мест нет». */
    @Test
    fun `session without id is dropped and negative seats become zero`() = runTest {
        server.enqueue(
            envelope("""[{"movieId":"$MOVIE"},{"id":"s-2","availableSeats":-3,"ticketPrice":-1}]"""),
        )

        val sessions = (
            repository().schedule(PLACE, LocalDate.of(2026, 9, 5)) as ApiResult.Success
            ).data

        assertEquals(1, sessions.size)
        assertEquals(0, sessions.single().availableSeats)
        assertEquals(0L, sessions.single().priceSum)
        assertTrue(sessions.single().isSoldOut)
    }

    // --- Покупка ---

    /** То, что приложение отправляет сейчас. Схемой тело не описано. */
    @Test
    fun `buy sends the seat under seatNumber`() = runTest {
        server.enqueue(envelope("""{"id":"t-1","sessionId":"$SESSION","seatNumber":"C7"}"""))

        repository().buy(SESSION, SeatChoice(" C7 "))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/cinema/sessions/$SESSION/buy", request.path)
        assertEquals("""{"seatNumber":"C7"}""", request.body.readUtf8())
    }

    /** Место необязательно: тогда уходит пустой объект, а не `null`. */
    @Test
    fun `buy without a seat sends an empty object`() = runTest {
        server.enqueue(envelope("""{"id":"t-1"}"""))

        repository().buy(SESSION, SeatChoice("   "))

        assertEquals("{}", server.takeRequest().body.readUtf8())
    }

    @Test
    fun `bought ticket is parsed`() = runTest {
        server.enqueue(
            envelope(
                """{"id":"t-1","sessionId":"$SESSION","seatNumber":"C7","price":45000,
                   "qrCode":"4820117499","status":"ACTIVE",
                   "createdAt":"2026-09-04T14:00:00"}""",
            ),
        )

        val ticket = (repository().buy(SESSION, SeatChoice("C7")) as ApiResult.Success).data

        assertEquals("t-1", ticket.id)
        assertEquals("C7", ticket.seatNumber)
        assertEquals(45_000L, ticket.priceSum)
        assertEquals("4820117499", ticket.code)
        assertEquals(CinemaTicketStatus.Active, ticket.status)
        // Jackson отдаёт `LocalDateTime` без зоны — иначе дата пуста у всех.
        assertEquals("2026-09-04T14:00:00Z", ticket.createdAt.toString())
    }

    /**
     * Ответ без `id` — не отказ: билет куплен, и найти его можно в «моих
     * билетах» (в отличие от талона очереди, issue #96).
     */
    @Test
    fun `bought ticket without id is still a success`() = runTest {
        server.enqueue(envelope("""{"seatNumber":"C7","status":"ACTIVE"}"""))

        val result = repository().buy(SESSION, SeatChoice("C7"))

        assertTrue(result is ApiResult.Success)
        assertEquals("", (result as ApiResult.Success).data.id)
    }

    /** Слишком длинное место сервер отверг бы — платой были бы запрос и ожидание. */
    @Test
    fun `too long seat never reaches the network`() = runTest {
        val result = repository().buy(SESSION, SeatChoice("x".repeat(SeatChoice.MAX_LENGTH + 1)))

        assertEquals(
            ApiError.Business(CinemaRepository.INVALID_REQUEST_CODE),
            (result as ApiResult.Failure).error,
        )
        assertEquals(0, server.requestCount)
    }

    // --- Мои билеты ---

    @Test
    fun `my tickets are requested by page`() = runTest {
        server.enqueue(
            envelope(
                """{"content":[{"id":"t-1","status":"ACTIVE"},{"status":"ACTIVE"},
                   {"id":"t-2","status":"USED"}],"page":0,"totalPages":3,"last":false}""",
            ),
        )

        val page = (repository().myTickets(page = 0) as ApiResult.Success).data

        val request = server.takeRequest()
        assertEquals("/cinema/tickets/my?page=0&size=${CinemaRepository.PAGE_SIZE}", request.path)
        // Билет без `id` вернуть нечем, и в `LazyColumn` это дубликат ключа.
        assertEquals(listOf("t-1", "t-2"), page.items.map(CinemaTicket::id))
        assertTrue(page.hasMore)
    }

    /** Молчание сервера о страницах останавливает догрузку. */
    @Test
    fun `page without paging fields stops the tail`() = runTest {
        server.enqueue(envelope("""{"content":[{"id":"t-1"}]}"""))

        val page = (repository().myTickets() as ApiResult.Success).data

        assertFalse(page.hasMore)
    }

    @Test
    fun `last page is computed from totalPages when last is missing`() = runTest {
        server.enqueue(envelope("""{"content":[{"id":"t-1"}],"page":2,"totalPages":3}"""))

        assertFalse((repository().myTickets(page = 2) as ApiResult.Success).data.hasMore)
    }

    // --- Возврат ---

    @Test
    fun `cancel puts to the ticket path without a body`() = runTest {
        server.enqueue(envelope("""{"id":"t-1","status":"CANCELLED"}"""))

        val result = repository().cancel(CinemaTicket(id = "t-1"))

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/cinema/tickets/t-1/cancel", request.path)
        assertEquals("", request.body.readUtf8())
        assertEquals(
            CinemaTicketStatus.Cancelled,
            (result as ApiResult.Success).data.status,
        )
    }

    /**
     * Сервер подтвердил возврат, но тела не прислал: состояние выводится из
     * самого факта — иначе удачный возврат выглядел бы как «не удалось».
     */
    @Test
    fun `cancel without a body still returns the ticket`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody("""{"success":true}"""),
        )

        val ticket = CinemaTicket(id = "t-1", status = CinemaTicketStatus.Active)
        val result = repository().cancel(ticket)

        assertEquals(
            CinemaTicketStatus.Cancelled,
            (result as ApiResult.Success).data.status,
        )
    }

    @Test
    fun `ticket without id never reaches the network`() = runTest {
        val result = repository().cancel(CinemaTicket(id = " "))

        assertTrue(result is ApiResult.Failure)
        assertEquals(0, server.requestCount)
    }

    // --- Конверт и отказы ---

    /** 2xx с `success:false` — отказ, а не пустой экран (issue #42). */
    @Test
    fun `envelope failure becomes a business error with the server code`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"SEAT_TAKEN",
                       "message":"Bu joy band"}}""",
                ),
        )

        val result = repository().buy(SESSION, SeatChoice("C7"))

        assertEquals(
            ApiError.Business("SEAT_TAKEN"),
            (result as ApiResult.Failure).error,
        )
    }

    /** Текст бэкенда доезжает до экрана как есть (issue #34). */
    @Test
    fun `http failure keeps the server message`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"SESSION_SOLD_OUT",
                       "message":"Bu seansda joy qolmadi"}}""",
                ),
        )

        val result = repository().buy(SESSION, SeatChoice(""))

        assertEquals(
            "Bu seansda joy qolmadi",
            (result as ApiResult.Failure).failure.server?.message,
        )
    }

    /** Фактический ответ стенда без токена — на нём и держится вход. */
    @Test
    fun `unauthorized ticket list is a failure`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"UNAUTHORIZED",
                       "message":"Kirish uchun autentifikatsiya talab qilinadi"}}""",
                ),
        )

        val result = repository().myTickets()

        assertEquals(ApiError.Unauthorized, (result as ApiResult.Failure).error)
    }

    private fun repository() = DefaultCinemaRepository(
        api = NetworkFactory
            .retrofit(
                server.url("/").toString(),
                NetworkFactory.clientBuilder().build(),
                NetworkFactory.converterFactory(NetworkFactory.json()),
            )
            .create(CinemaApi::class.java),
    )

    private fun envelope(data: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody("""{"success":true,"data":$data}""")

    private companion object {
        const val PLACE = "11111111-1111-1111-1111-111111111111"
        const val MOVIE = "22222222-2222-2222-2222-222222222222"
        const val SESSION = "33333333-3333-3333-3333-333333333333"
    }
}
