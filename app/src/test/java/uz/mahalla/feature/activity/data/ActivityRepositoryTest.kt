package uz.mahalla.feature.activity.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.feature.activity.domain.Activity
import uz.mahalla.feature.activity.domain.ActivityKind
import uz.mahalla.feature.activity.domain.ActivitySource
import uz.mahalla.feature.activity.domain.ActivityStatus
import uz.mahalla.feature.activity.domain.ActivityTarget
import java.time.Instant

/**
 * «Мои активности» (issue #73) на настоящем сетевом стеке ([NetworkFactory] +
 * [MockWebServer]): подмена Retrofit фейком не поймала бы ни ошибку в пути
 * запроса, ни несовпадение схемы JSON.
 *
 * Контракт снят со стенда (`/v3/api-docs` + curl'ы). Ответы приходят по
 * разным путям одновременно, поэтому сервер отвечает не очередью, а
 * [Dispatcher] по пути запроса: порядок пяти параллельных запросов не
 * определён, и `enqueue` раздал бы ответы вразнобой.
 */
class ActivityRepositoryTest {

    private lateinit var server: MockWebServer
    private val bodies = mutableMapOf<String, MockResponse>()
    private val requests = mutableMapOf<String, RecordedRequest>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                requests[path] = request
                return bodies[path] ?: envelope("""{"content":[],"last":true}""")
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `orders are read from the shared endpoint and land in the list`() = runTest {
        // Заказы читаются общей ручкой, а не `food/orders/my`: у неё схема
        // `OrderView` однозначна, а у food-ручки имя схемы перекрыто
        // коллизией springdoc.
        respond(
            "/orders",
            """{"content":[{"id":"o-1","orderNumber":"F-2026-0042","placeId":"p-1",
               "vertical":"FOOD","status":"PREPARING","fulfillment":"DELIVERY",
               "paymentMethod":"WALLET","itemsAmount":70000,"deliveryAmount":14000,
               "totalAmount":84000,"createdAt":"2026-09-04T08:10:00"}],
               "page":0,"last":true}""",
        )

        val feed = repository().feed()

        assertEquals("/orders?page=0&size=20", requests.getValue("/orders").path)
        val order = feed.items.single()
        assertEquals("o-1", order.id)
        assertEquals(ActivitySource.Orders, order.source)
        assertEquals(ActivityKind.FoodOrder, order.kind)
        assertEquals(ActivityStatus.InProgress, order.status)
        assertEquals(84_000L, order.amount)
        assertEquals("F-2026-0042", order.note)
        // Jackson на бэкенде отдаёт дату без зоны — иначе она пуста у всех.
        assertEquals(Instant.parse("2026-09-04T08:10:00Z"), order.occurredAt)
        assertEquals(ActivityTarget.FoodOrder("o-1"), order.target)
        assertTrue(feed.failures.isEmpty())
        assertFalse(feed.hasMore)
    }

    @Test
    fun `only food orders are clickable`() = runTest {
        // Экран статуса построен на домене «Еды» (этапы кухни, «повторить» в
        // её корзину): заказ одежды открылся бы там под видом заказа еды.
        respond(
            "/orders",
            """{"content":[{"id":"o-1","vertical":"FOOD","status":"NEW"},
               {"id":"o-2","vertical":"CLOTHING","status":"NEW"},
               {"id":"o-3","vertical":"PHARMACY","status":"NEW"}],"last":true}""",
        )

        val byId = repository().feed().items.associateBy(Activity::id)

        assertEquals(ActivityTarget.FoodOrder("o-1"), byId.getValue("o-1").target)
        assertEquals(ActivityTarget.None, byId.getValue("o-2").target)
        assertEquals(ActivityKind.ClothingOrder, byId.getValue("o-2").kind)
        assertEquals(ActivityTarget.None, byId.getValue("o-3").target)
    }

    @Test
    fun `a gaming booking is sorted by its start time`() = runTest {
        // В списке активностей ищут «когда я играю», а не «когда я нажал
        // кнопку».
        respond(
            "/gaming/bookings/my",
            """{"content":[{"id":"b-1","placeId":"p-2","zoneId":"z-1",
               "startTime":"2026-09-05T13:00:00","endTime":"2026-09-05T15:00:00",
               "durationHours":2,"totalPrice":60000,"status":"CONFIRMED",
               "createdAt":"2026-09-01T09:00:00"}],"last":true}""",
        )

        val booking = repository().feed().items.single()

        assertEquals(
            "/gaming/bookings/my?page=0&size=20",
            requests.getValue("/gaming/bookings/my").path,
        )
        assertEquals(ActivitySource.GamingBookings, booking.source)
        assertEquals(ActivityKind.GamingBooking, booking.kind)
        assertEquals(ActivityStatus.Confirmed, booking.status)
        assertEquals(60_000L, booking.amount)
        assertEquals(Instant.parse("2026-09-05T13:00:00Z"), booking.occurredAt)
    }

    @Test
    fun `a booking without a start time falls back to its creation time`() = runTest {
        respond(
            "/gaming/bookings/my",
            """{"content":[{"id":"b-1","status":"ACTIVE",
               "createdAt":"2026-09-01T09:00:00"}],"last":true}""",
        )

        // Иначе бронь ушла бы в конец списка к записям без даты.
        assertEquals(
            Instant.parse("2026-09-01T09:00:00Z"),
            repository().feed().items.single().occurredAt,
        )
    }

    @Test
    fun `an appointment date and LocalTime become one moment in Tashkent`() = runTest {
        // `startTime` приходит объектом `{hour, minute, second, nano}`:
        // Jackson так сериализует `java.time.LocalTime` без `JavaTimeModule`.
        respond(
            "/appointments/my",
            """{"content":[{"id":"a-1","placeId":"p-3","serviceId":"s-1",
               "serviceName":"Soch olish","price":45000,"apptDate":"2026-09-10",
               "startTime":{"hour":9,"minute":30,"second":0,"nano":0},
               "status":"PENDING","createdAt":"2026-09-01T07:00:00"}],"last":true}""",
        )

        val appointment = repository().feed().items.single()

        assertEquals(ActivitySource.MasterAppointments, appointment.source)
        assertEquals(ActivityKind.MasterAppointment, appointment.kind)
        assertEquals(ActivityStatus.Placed, appointment.status)
        assertEquals(45_000L, appointment.amount)
        assertEquals("Soch olish", appointment.note)
        // 09:30 в Ташкенте — это 04:30 UTC. Разворачивать местную дату в UTC
        // значило бы показать запись на пять часов позже.
        assertEquals(Instant.parse("2026-09-10T04:30:00Z"), appointment.occurredAt)
    }

    @Test
    fun `an appointment without a time keeps its date`() = runTest {
        respond(
            "/appointments/my",
            """{"content":[{"id":"a-1","apptDate":"2026-09-10","status":"CONFIRMED"}],
               "last":true}""",
        )

        assertEquals(
            Instant.parse("2026-09-09T19:00:00Z"),
            repository().feed().items.single().occurredAt,
        )
    }

    @Test
    fun `a broken time does not invent a moment`() = runTest {
        // Собранное из мусора время хуже отсутствующего: человек поверит
        // цифрам на экране.
        respond(
            "/appointments/my",
            """{"content":[{"id":"a-1","apptDate":"10-09-2026",
               "startTime":{"hour":31,"minute":99},"status":"CONFIRMED"}],"last":true}""",
        )

        assertNull(repository().feed().items.single().occurredAt)
    }

    @Test
    fun `doctor appointments come from their own endpoint with the same schema`() = runTest {
        respond(
            "/hospitals/appointments/my",
            """{"content":[{"id":"h-1","serviceName":"Terapevt","apptDate":"2026-09-11",
               "status":"NO_SHOW"}],"last":true}""",
        )

        val appointment = repository().feed().items.single()

        assertEquals(ActivitySource.DoctorAppointments, appointment.source)
        assertEquals(ActivityKind.DoctorAppointment, appointment.kind)
        assertEquals(ActivityStatus.Missed, appointment.status)
    }

    @Test
    fun `a cinema ticket carries its seat`() = runTest {
        respond(
            "/cinema/tickets/my",
            """{"content":[{"id":"t-1","sessionId":"s-9","seatNumber":"D-12",
               "price":35000,"qrCode":"QR","status":"ACTIVE",
               "createdAt":"2026-09-02T15:00:00"}],"last":true}""",
        )

        val ticket = repository().feed().items.single()

        assertEquals(ActivitySource.CinemaTickets, ticket.source)
        assertEquals(ActivityKind.CinemaTicket, ticket.kind)
        // `ACTIVE` у билета — «действителен», а не «идёт сейчас».
        assertEquals(ActivityStatus.Confirmed, ticket.status)
        assertEquals("D-12", ticket.note)
        assertEquals(35_000L, ticket.amount)
    }

    @Test
    fun `all five sources merge into one list`() = runTest {
        respond("/orders", """{"content":[{"id":"o-1","vertical":"FOOD","status":"NEW"}],"last":true}""")
        respond(
            "/gaming/bookings/my",
            """{"content":[{"id":"b-1","status":"CONFIRMED"}],"last":true}""",
        )
        respond("/appointments/my", """{"content":[{"id":"a-1","status":"PENDING"}],"last":true}""")
        respond(
            "/hospitals/appointments/my",
            """{"content":[{"id":"h-1","status":"PENDING"}],"last":true}""",
        )
        respond("/cinema/tickets/my", """{"content":[{"id":"t-1","status":"ACTIVE"}],"last":true}""")

        val feed = repository().feed()

        assertEquals(
            ActivitySource.entries.toSet(),
            feed.items.map(Activity::source).toSet(),
        )
        assertEquals(5, feed.items.size)
        assertEquals(ActivitySource.entries.toSet(), feed.requested)
    }

    @Test
    fun `one failed source does not take the others down`() = runTest {
        // Главное требование T7: четыре источника с данными и один с ошибкой —
        // это список плюс отметка о сбойном разделе, а не пустой экран.
        respond("/orders", """{"content":[{"id":"o-1","vertical":"FOOD","status":"NEW"}],"last":true}""")
        bodies["/cinema/tickets/my"] = MockResponse().setResponseCode(500)

        val feed = repository().feed()

        assertEquals(listOf("o-1"), feed.items.map(Activity::id))
        assertEquals(setOf(ActivitySource.CinemaTickets), feed.failures.keys)
        assertTrue(feed.isPartial)
        assertFalse(feed.isTotalFailure)
    }

    @Test
    fun `an expired session fails every source`() = runTest {
        ALL_PATHS.forEach { bodies[it] = MockResponse().setResponseCode(401) }

        val feed = repository().feed()

        assertTrue(feed.isTotalFailure)
        assertEquals(
            ApiError.Unauthorized,
            feed.failures.getValue(ActivitySource.Orders).error,
        )
    }

    @Test
    fun `a 2xx envelope with success false is a failure of that source only`() = runTest {
        respond("/orders", """{"content":[{"id":"o-1","vertical":"FOOD","status":"NEW"}],"last":true}""")
        bodies["/appointments/my"] = MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
            .setBody(
                """{"success":false,"error":{"code":"APPOINTMENTS_UNAVAILABLE",
                   "message":"Yozilishlar vaqtincha ishlamayapti"}}""",
            )

        val feed = repository().feed()

        val failure = feed.failures.getValue(ActivitySource.MasterAppointments)
        assertEquals(ApiError.Business("APPOINTMENTS_UNAVAILABLE"), failure.error)
        assertEquals("Yozilishlar vaqtincha ishlamayapti", failure.serverMessage)
        assertEquals(1, feed.items.size)
    }

    @Test
    fun `a record without an id is dropped, not shown`() = runTest {
        // В LazyColumn это дубликат ключа, а отличить такую запись от соседней
        // всё равно нечем.
        respond(
            "/orders",
            """{"content":[{"vertical":"FOOD","status":"NEW"},
               {"id":"","vertical":"FOOD","status":"NEW"},
               {"id":"o-2","vertical":"FOOD","status":"NEW"}],"last":true}""",
        )

        assertEquals(listOf("o-2"), repository().feed().items.map(Activity::id))
    }

    @Test
    fun `an activity without a status or amount stays in the list`() = runTest {
        // За ней стоят потраченные деньги: «заказ исчез» страшнее «заказ без
        // даты».
        respond("/orders", """{"content":[{"id":"o-1"}],"last":true}""")

        val order = repository().feed().items.single()

        assertEquals(ActivityStatus.Unknown, order.status)
        assertEquals(ActivityKind.OtherOrder, order.kind)
        assertNull(order.amount)
        assertNull(order.occurredAt)
    }

    // --- Пагинация ---

    @Test
    fun `the next page is only asked of sources that have one`() = runTest {
        respond(
            "/orders",
            """{"content":[{"id":"o-1","vertical":"FOOD","status":"NEW"}],"page":0,"last":false}""",
        )
        respond("/cinema/tickets/my", """{"content":[{"id":"t-1"}],"page":0,"last":true}""")

        val feed = repository().feed()

        // Просить у исчерпанного источника следующую страницу значит получать
        // один и тот же хвост заново.
        assertEquals(mapOf(ActivitySource.Orders to 1), feed.nextPages)
        assertTrue(feed.hasMore)
    }

    @Test
    fun `hasMore falls back to page and totalPages`() = runTest {
        respond("/orders", """{"content":[{"id":"o-1"}],"page":0,"totalPages":3}""")

        assertEquals(mapOf(ActivitySource.Orders to 1), repository().feed().nextPages)
    }

    @Test
    fun `silence about pages stops the pagination`() = runTest {
        // Лучше не показать хвост, чем зациклить запрос одной и той же
        // страницы.
        respond("/orders", """{"content":[{"id":"o-1"}]}""")

        assertTrue(repository().feed().nextPages.isEmpty())
    }

    @Test
    fun `load more asks the given page only of the given sources`() = runTest {
        respond("/orders", """{"content":[{"id":"o-9","vertical":"FOOD"}],"last":true}""")

        val feed = repository().feed(pages = mapOf(ActivitySource.Orders to 2))

        assertEquals("/orders?page=2&size=20", requests.getValue("/orders").path)
        assertEquals(setOf("/orders"), requests.keys)
        assertEquals(setOf(ActivitySource.Orders), feed.requested)
    }

    private fun respond(path: String, data: String) {
        bodies[path] = envelope(data)
    }

    private fun repository() = DefaultActivityRepository(
        NetworkFactory
            .retrofit(
                server.url("/").toString(),
                NetworkFactory.clientBuilder().build(),
                NetworkFactory.converterFactory(NetworkFactory.json()),
            )
            .create(ActivityApi::class.java),
    )

    private fun envelope(data: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody("""{"success":true,"data":$data}""")

    private companion object {
        val ALL_PATHS = listOf(
            "/orders",
            "/gaming/bookings/my",
            "/appointments/my",
            "/hospitals/appointments/my",
            "/cinema/tickets/my",
        )
    }
}
