package uz.mahalla.feature.booking.data

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
import uz.mahalla.feature.booking.domain.Appointment
import uz.mahalla.feature.booking.domain.AppointmentStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Бронирование (issue #97) на настоящем сетевом стеке ([NetworkFactory] +
 * [MockWebServer]): подмена Retrofit фейком не поймала бы ни ошибку в пути
 * запроса, ни несовпадение схемы JSON.
 *
 * Контракт снят со стенда 2026-09-04. Услуги и слоты анонимны (`200` без
 * токена), запись, список и отмена требуют Bearer (`401`). Тело
 * `POST appointments` подтвердить живым запросом нельзя — `401` приходит до
 * валидации, а схема `BookRequest` перекрыта коллизией springdoc; тест
 * закрепляет то, что приложение отправляет **сейчас**, чтобы правка после
 * проверки под токеном была видна одной строкой.
 */
class BookingRepositoryTest {

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
    fun `services are requested by place and parsed out of the envelope`() = runTest {
        server.enqueue(
            envelope(
                """[{"id":"s-1","title":"Soch olish","description":"Mashinka bilan",
                   "priceAmount":60000,"durationMinutes":40,"isActive":true}]""",
            ),
        )

        val services = (repository().services(PLACE) as ApiResult.Success).data

        assertEquals("/barber-services/places/$PLACE", server.takeRequest().path)
        val service = services.single()
        assertEquals("s-1", service.id)
        assertEquals("Soch olish", service.title)
        assertEquals("Mashinka bilan", service.description)
        assertEquals(60_000L, service.priceSum)
        assertEquals(40, service.durationMinutes)
    }

    @Test
    fun `a service that cannot be booked is not offered`() = runTest {
        // Выключенная услуга и услуга без `id`: записаться нельзя ни на одну,
        // а строка, которая ничего не делает, читается как сломанная.
        server.enqueue(
            envelope(
                """[{"id":"s-1","title":"Soch olish"},
                   {"id":"s-2","title":"Soqol","isActive":false},
                   {"id":"s-3","title":"Massaj","active":false},
                   {"title":"Nomsiz"}]""",
            ),
        )

        val services = (repository().services(PLACE) as ApiResult.Success).data

        assertEquals(listOf("s-1"), services.map { it.id })
        // Молчание сервера о флаге — «услуга оказывается».
        assertTrue(services.single().isActive)
    }

    @Test
    fun `slots are requested for a service and a day, and the past is dropped`() = runTest {
        server.enqueue(envelope("""["09:00:00","14:00:00","14:30:00"]"""))

        val slots = (
            repository().slots(PLACE, SERVICE, LocalDate.of(2026, 9, 4)) as ApiResult.Success
            ).data

        assertEquals(
            "/barber-services/places/$PLACE/slots?serviceId=$SERVICE&date=2026-09-04",
            server.takeRequest().path,
        )
        // NOW — 14:00 в Ташкенте: утренний слот сервер отдал, но предлагать его
        // уже нельзя (issue #97).
        assertEquals(listOf(LocalTime.of(14, 0), LocalTime.of(14, 30)), slots)
    }

    @Test
    fun `an unknown service is a server failure with its own text`() = runTest {
        // Ровно то, что отвечает стенд на выдуманный `serviceId`.
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"NOT_FOUND",
                       "message":"Xizmat topilmadi: $SERVICE"}}""",
                ),
        )

        val failure = (
            repository().slots(PLACE, SERVICE, LocalDate.of(2026, 9, 5)) as ApiResult.Failure
            ).failure

        assertEquals("NOT_FOUND", failure.server?.code)
        assertEquals("Xizmat topilmadi: $SERVICE", failure.serverMessage)
    }

    /**
     * Тело записи. Имена полей выведены, а не прочитаны (см.
     * [BookAppointmentRequest]) — тест фиксирует их, чтобы расхождение с
     * бэкендом чинилось в одном месте и было заметно в диффе.
     */
    @Test
    fun `booking sends the place, the service, the day and the time`() = runTest {
        server.enqueue(envelope("""{"id":"a-1","status":"PENDING"}"""))

        val result = repository().book(
            placeId = PLACE,
            serviceId = SERVICE,
            date = LocalDate.of(2026, 9, 5),
            time = LocalTime.of(10, 30),
        )

        val request = server.takeRequest()
        assertEquals("/appointments", request.path)
        assertEquals("POST", request.method)
        assertEquals(
            """{"placeId":"$PLACE","serviceId":"$SERVICE",""" +
                """"date":"2026-09-05","startTime":"10:30:00"}""",
            request.body.readUtf8(),
        )
        assertEquals("a-1", (result as ApiResult.Success).data.id)
        assertEquals(AppointmentStatus.Pending, result.data.status)
    }

    @Test
    fun `an answer without an id is still a booking`() = runTest {
        // В отличие от талона очереди (issue #96) запись можно найти в «моих
        // записях» — отказом такой ответ не считается.
        server.enqueue(envelope("""{"status":"PENDING","apptDate":"2026-09-05"}"""))

        val appointment = (
            repository().book(PLACE, SERVICE, LocalDate.of(2026, 9, 5), LocalTime.of(10, 30))
                as ApiResult.Success
            ).data

        assertEquals("", appointment.id)
        assertFalse(appointment.canCancel)
        assertEquals(LocalDate.of(2026, 9, 5), appointment.date)
    }

    @Test
    fun `a passed slot never reaches the network`() = runTest {
        // 09:00 в Ташкенте сегодня уже прошло: сервер ответил бы тем же
        // отказом, но платой были бы запрос и молчание экрана.
        val result = repository().book(
            placeId = PLACE,
            serviceId = SERVICE,
            date = LocalDate.of(2026, 9, 4),
            time = LocalTime.of(9, 0),
        )

        assertEquals(
            ApiError.Business(BookingRepository.INVALID_REQUEST_CODE),
            (result as ApiResult.Failure).error,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an empty service never reaches the network either`() = runTest {
        val result = repository().book(
            placeId = PLACE,
            serviceId = "",
            date = LocalDate.of(2026, 9, 5),
            time = LocalTime.of(10, 0),
        )

        assertEquals(
            ApiError.Business(BookingRepository.INVALID_REQUEST_CODE),
            (result as ApiResult.Failure).error,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the time is read both as a string and as an object`() = runTest {
        // springdoc описывает `LocalTime` объектом, Jackson отдаёт строку.
        // Ошибка в типе уронила бы разбор всей записи.
        server.enqueue(
            envelope(
                """{"content":[
                   {"id":"a-1","startTime":"10:30:00","endTime":{"hour":11,"minute":10}},
                   {"id":"a-2","startTime":{"hour":9,"minute":0,"second":0,"nano":0}}
                   ],"last":true}""",
            ),
        )

        val items = (repository().myAppointments() as ApiResult.Success).data.items

        assertEquals(LocalTime.of(10, 30), items[0].startTime)
        assertEquals(LocalTime.of(11, 10), items[0].endTime)
        assertEquals(LocalTime.of(9, 0), items[1].startTime)
    }

    @Test
    fun `my appointments come by page and drop what cannot be shown`() = runTest {
        server.enqueue(
            envelope(
                """{"content":[
                   {"id":"a-1","placeId":"$PLACE","serviceId":"$SERVICE",
                    "serviceName":"Soch olish","price":60000,"apptDate":"2026-09-05",
                    "startTime":"10:30:00","status":"CONFIRMED",
                    "createdAt":"2026-09-04T12:00:00"},
                   {"serviceName":"Yozuvsiz"},
                   {"id":"a-3","apptDate":"kecha","status":"WHATEVER"}
                   ],"page":0,"totalPages":2}""",
            ),
        )

        val page = (repository().myAppointments() as ApiResult.Success).data

        assertEquals("/appointments/my?page=0&size=20", server.takeRequest().path)
        // Запись без `id` отброшена: отменить её нечем, а в списке это дубликат
        // ключа.
        assertEquals(listOf("a-1", "a-3"), page.items.map(Appointment::id))
        val first = page.items.first()
        assertEquals("Soch olish", first.serviceName)
        assertEquals(60_000L, first.priceSum)
        assertEquals(LocalDate.of(2026, 9, 5), first.date)
        assertEquals(AppointmentStatus.Confirmed, first.status)
        // Jackson отдаёт `LocalDateTime` без зоны — иначе дата пуста у всех.
        assertEquals(Instant.parse("2026-09-04T12:00:00Z"), first.createdAt)
        // Битая дата и незнакомый статус запись не прячут.
        assertNull(page.items[1].date)
        assertEquals(AppointmentStatus.Unknown, page.items[1].status)
        // `last` не пришёл — считаем по `page`/`totalPages`.
        assertTrue(page.hasMore)
    }

    @Test
    fun `silence about pages stops the paging`() = runTest {
        server.enqueue(envelope("""{"content":[{"id":"a-1"}]}"""))

        val page = (repository().myAppointments() as ApiResult.Success).data

        assertFalse(page.hasMore)
    }

    @Test
    fun `cancelling goes by id and carries no body`() = runTest {
        server.enqueue(envelope("""{"id":"a-1","status":"CANCELLED"}"""))

        val result = repository().cancel(appointment())

        val request = server.takeRequest()
        assertEquals("/appointments/a-1/cancel", request.path)
        assertEquals("POST", request.method)
        assertEquals("", request.body.readUtf8())
        assertEquals(AppointmentStatus.Cancelled, (result as ApiResult.Success).data.status)
    }

    @Test
    fun `a cancellation without a body is still a cancellation`() = runTest {
        // `ensureSuccess` уже подтвердил отмену: разбирать тело обязательным
        // не считаем, иначе удачная отмена выглядела бы как «не удалось».
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody("""{"success":true}"""),
        )

        val result = repository().cancel(appointment())

        assertEquals(AppointmentStatus.Cancelled, (result as ApiResult.Success).data.status)
    }

    @Test
    fun `a refused cancellation is reported with the text of the backend`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"APPOINTMENT_ALREADY_STARTED",
                       "message":"Xizmat boshlangan, bekor qilib bo'lmaydi"}}""",
                ),
        )

        val failure = (repository().cancel(appointment()) as ApiResult.Failure).failure

        assertEquals("APPOINTMENT_ALREADY_STARTED", failure.server?.code)
        assertEquals("Xizmat boshlangan, bekor qilib bo'lmaydi", failure.serverMessage)
    }

    @Test
    fun `an appointment without an id is not cancelled over the network`() = runTest {
        val result = repository().cancel(appointment(id = ""))

        assertEquals(
            ApiError.Business(BookingRepository.INVALID_REQUEST_CODE),
            (result as ApiResult.Failure).error,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `success false is a failure, not an empty list`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"SERVICE_UNAVAILABLE",
                       "message":"Vaqtincha ishlamayapti"}}""",
                ),
        )

        val failure = (repository().services(PLACE) as ApiResult.Failure).failure

        assertEquals(ApiError.Business("SERVICE_UNAVAILABLE"), failure.error)
        assertEquals("Vaqtincha ishlamayapti", failure.serverMessage)
    }

    @Test
    fun `the 401 the stand returns without a token stays unauthorized`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"UNAUTHORIZED",
                       "message":"Kirish uchun autentifikatsiya talab qilinadi"}}""",
                ),
        )

        val failure = (repository().myAppointments() as ApiResult.Failure).failure

        assertEquals(ApiError.Unauthorized, failure.error)
        assertEquals("Kirish uchun autentifikatsiya talab qilinadi", failure.serverMessage)
    }

    private fun appointment(id: String = "a-1") = Appointment(
        id = id,
        date = LocalDate.of(2026, 9, 5),
        startTime = LocalTime.of(10, 30),
        status = AppointmentStatus.Pending,
    )

    private fun repository() = DefaultBookingRepository(
        api = NetworkFactory
            .retrofit(
                server.url("/").toString(),
                NetworkFactory.clientBuilder().build(),
                NetworkFactory.converterFactory(NetworkFactory.json()),
            )
            .create(BookingApi::class.java),
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
    )

    private fun envelope(data: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody("""{"success":true,"data":$data}""")

    private companion object {
        /** 09:00 UTC = 14:00 в Ташкенте, 4 сентября. */
        val NOW: Instant = Instant.parse("2026-09-04T09:00:00Z")
        const val PLACE = "11111111-1111-1111-1111-111111111111"
        const val SERVICE = "22222222-2222-2222-2222-222222222222"
    }
}
