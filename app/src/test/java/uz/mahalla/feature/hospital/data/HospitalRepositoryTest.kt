package uz.mahalla.feature.hospital.data

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
import uz.mahalla.feature.booking.data.BookingRepository
import uz.mahalla.feature.booking.domain.Appointment
import uz.mahalla.feature.booking.domain.AppointmentStatus
import uz.mahalla.feature.hospital.domain.DoctorAppointmentDraft
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Больницы (issue #99) на настоящем сетевом стеке ([NetworkFactory] +
 * [MockWebServer]): подмена Retrofit фейком не поймала бы ни ошибку в пути
 * запроса, ни несовпадение схемы JSON.
 *
 * Контракт снят со стенда 2026-09-04. Список врачей анонимен (`200` без
 * токена), запись, список и отмена требуют Bearer (`401`). Тело
 * `POST hospitals/appointments` подтвердить живым запросом нельзя — `401`
 * приходит до валидации; тест закрепляет то, что приложение отправляет
 * **сейчас**, чтобы правка после проверки под токеном была видна одной
 * строкой.
 */
class HospitalRepositoryTest {

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
    fun `doctors are requested by place and parsed out of the envelope`() = runTest {
        server.enqueue(
            envelope(
                """[{"id":"d-1","name":"Aliyev Bekzod","specialty":"Terapevt",
                   "bio":"20 yillik tajriba","consultationPrice":90000}]""",
            ),
        )

        val doctors = (repository().doctors(PLACE) as ApiResult.Success).data

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/hospitals/places/$PLACE/doctors", request.path)
        assertEquals(1, doctors.size)
        assertEquals("Aliyev Bekzod", doctors.first().name)
        assertEquals("Terapevt", doctors.first().specialty)
        assertEquals(90_000L, doctors.first().consultationPriceSum)
    }

    /** Врача без `id` записывать нечем: `doctorId` обязателен в теле запроса. */
    @Test
    fun `doctor without id is dropped and the rest survives`() = runTest {
        server.enqueue(
            envelope(
                """[{"name":"Ismsiz"},{"id":"  "},
                   {"id":"d-2","consultationPrice":-5}]""",
            ),
        )

        val doctors = (repository().doctors(PLACE) as ApiResult.Success).data

        assertEquals(listOf("d-2"), doctors.map { it.id })
        // Отрицательная цена — мусор, а не скидка: показывается как «не названа».
        assertEquals(0L, doctors.first().consultationPriceSum)
        assertEquals("", doctors.first().name)
        assertNull(doctors.first().specialty)
    }

    /** Пустой каталог — это ответ стенда сегодня, и он не ошибка. */
    @Test
    fun `empty doctor list is a success`() = runTest {
        server.enqueue(envelope("[]"))

        assertTrue((repository().doctors(PLACE) as ApiResult.Success).data.isEmpty())
    }

    @Test
    fun `booking sends doctor date time and complaint`() = runTest {
        server.enqueue(
            envelope(
                """{"id":"a-1","serviceName":"Aliyev Bekzod","apptDate":"2026-09-05",
                   "startTime":"09:30:00","status":"PENDING"}""",
            ),
        )

        val result = repository().book(
            DoctorAppointmentDraft(
                doctorId = DOCTOR,
                date = LocalDate.of(2026, 9, 5),
                time = LocalTime.of(9, 30),
                complaint = "  tomoq og'riyapti  ",
            ),
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/hospitals/appointments", request.path)
        assertEquals(
            """{"doctorId":"$DOCTOR","date":"2026-09-05","startTime":"09:30:00",""" +
                """"complaint":"tomoq og'riyapti"}""",
            request.body.readUtf8(),
        )
        val appointment = (result as ApiResult.Success).data
        assertEquals("a-1", appointment.id)
        assertEquals(LocalTime.of(9, 30), appointment.startTime)
        assertEquals(AppointmentStatus.Pending, appointment.status)
    }

    /** Пустая жалоба уходит отсутствующим полем, а не `"complaint":null`. */
    @Test
    fun `empty complaint is omitted from the body`() = runTest {
        server.enqueue(envelope("""{"id":"a-1"}"""))

        repository().book(
            DoctorAppointmentDraft(
                doctorId = DOCTOR,
                date = LocalDate.of(2026, 9, 5),
                time = LocalTime.of(9, 30),
                complaint = "   ",
            ),
        )

        assertEquals(
            """{"doctorId":"$DOCTOR","date":"2026-09-05","startTime":"09:30:00"}""",
            server.takeRequest().body.readUtf8(),
        )
    }

    /**
     * springdoc описывает `LocalTime` объектом, Jackson отдаёт строку — разбор
     * обязан принимать оба вида, иначе одна запись роняет весь ответ.
     */
    @Test
    fun `start time is parsed both as object and as string`() = runTest {
        server.enqueue(
            envelope(
                """{"content":[
                   {"id":"a-1","startTime":{"hour":9,"minute":30,"second":0,"nano":0}},
                   {"id":"a-2","startTime":"14:05:00"}],"last":true}""",
            ),
        )

        val page = (repository().myAppointments() as ApiResult.Success).data

        assertEquals(LocalTime.of(9, 30), page.items[0].startTime)
        assertEquals(LocalTime.of(14, 5), page.items[1].startTime)
    }

    @Test
    fun `my appointments are requested with paging from the hospital endpoint`() = runTest {
        server.enqueue(
            envelope(
                """{"content":[{"id":"a-1","serviceName":"Terapevt","apptDate":"2026-09-05",
                   "status":"CONFIRMED"}],"page":0,"totalPages":3}""",
            ),
        )

        val page = (repository().myAppointments(page = 0) as ApiResult.Success).data

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/hospitals/appointments/my?page=0&size=20", request.path)
        assertEquals(1, page.items.size)
        assertEquals(AppointmentStatus.Confirmed, page.items.first().status)
        // `last` не пришёл — считаем по `page`/`totalPages`.
        assertTrue(page.hasMore)
    }

    @Test
    fun `appointment without id is dropped from the list`() = runTest {
        server.enqueue(
            envelope("""{"content":[{"serviceName":"Terapevt"},{"id":"a-2"}],"last":true}"""),
        )

        val page = (repository().myAppointments() as ApiResult.Success).data

        assertEquals(listOf("a-2"), page.items.map { it.id })
        assertFalse(page.hasMore)
    }

    /**
     * Своей отмены у больниц нет — идём в общую ручку записи. Тела у запроса
     * нет.
     */
    @Test
    fun `cancel goes to the shared appointments endpoint`() = runTest {
        server.enqueue(envelope("""{"id":"a-1","status":"CANCELLED"}"""))

        val result = repository().cancel(Appointment(id = "a-1"))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/appointments/a-1/cancel", request.path)
        assertEquals("", request.body.readUtf8())
        assertEquals(
            AppointmentStatus.Cancelled,
            (result as ApiResult.Success).data.status,
        )
    }

    /**
     * Успешный ответ без тела — всё равно отмена: `ensureSuccess` уже
     * подтвердил её, и рисовать «не удалось» поверх сделанного нельзя.
     */
    @Test
    fun `cancel without a body still reports cancelled`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody("""{"success":true}"""),
        )

        val result = repository().cancel(
            Appointment(id = "a-1", status = AppointmentStatus.Pending),
        )

        assertEquals(
            AppointmentStatus.Cancelled,
            (result as ApiResult.Success).data.status,
        )
    }

    @Test
    fun `incomplete draft never reaches the network`() = runTest {
        val result = repository().book(DoctorAppointmentDraft(doctorId = DOCTOR))

        assertEquals(
            ApiError.Business(BookingRepository.INVALID_REQUEST_CODE),
            (result as ApiResult.Failure).error,
        )
        assertEquals(0, server.requestCount)
    }

    /** Прошедшее время — тоже отказ до сети: сервер сказал бы то же самое. */
    @Test
    fun `past time never reaches the network`() = runTest {
        val result = repository().book(
            DoctorAppointmentDraft(
                doctorId = DOCTOR,
                date = LocalDate.of(2026, 9, 4),
                // 09:00 UTC = 14:00 в Ташкенте, значит 09:00 уже прошло.
                time = LocalTime.of(9, 0),
            ),
        )

        assertEquals(
            ApiError.Business(BookingRepository.INVALID_REQUEST_CODE),
            (result as ApiResult.Failure).error,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `too long complaint never reaches the network`() = runTest {
        val result = repository().book(
            DoctorAppointmentDraft(
                doctorId = DOCTOR,
                date = LocalDate.of(2026, 9, 5),
                time = LocalTime.of(9, 30),
                complaint = "a".repeat(DoctorAppointmentDraft.MAX_COMPLAINT_LENGTH + 1),
            ),
        )

        assertTrue(result is ApiResult.Failure)
        assertEquals(0, server.requestCount)
    }

    /** 2xx с `success:false` — отказ, а не пустой экран (issue #42). */
    @Test
    fun `envelope failure becomes a business error with the server text`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"SLOT_TAKEN",
                       "message":"Bu vaqt band"}}""",
                ),
        )

        val result = repository().book(
            DoctorAppointmentDraft(
                doctorId = DOCTOR,
                date = LocalDate.of(2026, 9, 5),
                time = LocalTime.of(9, 30),
            ),
        )

        assertEquals(
            ApiError.Business("SLOT_TAKEN"),
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
                    """{"success":false,"error":{"code":"DOCTOR_BUSY",
                       "message":"Shifokor bu vaqtda band"}}""",
                ),
        )

        val result = repository().book(
            DoctorAppointmentDraft(
                doctorId = DOCTOR,
                date = LocalDate.of(2026, 9, 5),
                time = LocalTime.of(9, 30),
            ),
        )

        assertEquals(
            "Shifokor bu vaqtda band",
            (result as ApiResult.Failure).failure.server?.message,
        )
    }

    /** Фактический ответ стенда без токена — на нём и держится вход. */
    @Test
    fun `unauthorized list is a failure`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"UNAUTHORIZED",
                       "message":"Kirish uchun autentifikatsiya talab qilinadi"}}""",
                ),
        )

        val result = repository().myAppointments()

        assertEquals(ApiError.Unauthorized, (result as ApiResult.Failure).error)
    }

    private fun repository() = DefaultHospitalRepository(
        api = NetworkFactory
            .retrofit(
                server.url("/").toString(),
                NetworkFactory.clientBuilder().build(),
                NetworkFactory.converterFactory(NetworkFactory.json()),
            )
            .create(HospitalApi::class.java),
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
        const val DOCTOR = "33333333-3333-3333-3333-333333333333"
    }
}
