package uz.mahalla.feature.services.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.feature.onboarding.domain.PhoneNumberValidator
import uz.mahalla.feature.services.domain.ServiceOfferForm
import uz.mahalla.feature.services.domain.ServiceOrderForm
import uz.mahalla.feature.services.domain.ServiceRequestStatus
import java.time.Instant

/**
 * Услуги (issue #71) на настоящем сетевом стеке ([NetworkFactory] +
 * [MockWebServer]): подмена Retrofit фейком не поймала бы ни ошибку в пути
 * запроса, ни несовпадение схемы JSON.
 *
 * Контракт снят со стенда: `POST walkin/send` → `Response`,
 * `GET`/`POST freelancers/me` → `ProfileResponse`,
 * `PUT freelancers/me/toggle-availability` → пустой конверт.
 */
class ServicesRepositoryTest {

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
    fun `service order sends the form fields the backend asks for`() = runTest {
        server.enqueue(envelope("""{"id":"w-1","status":"PENDING"}"""))

        repository().sendServiceOrder(
            placeId = "p-1",
            form = ServiceOrderForm(customerName = "  Aziz  ", serviceName = " Soch olish "),
        )

        val request = server.takeRequest()
        assertEquals("/walkin/send", request.path)
        assertEquals("POST", request.method)
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("\"placeId\":\"p-1\""))
        // Пробелы по краям на сервер не уезжают: подпись в очереди мастера
        // читает человек.
        assertTrue(body, body.contains("\"userName\":\"Aziz\""))
        assertTrue(body, body.contains("\"serviceName\":\"Soch olish\""))
    }

    @Test
    fun `service order reads the state of the request`() = runTest {
        server.enqueue(
            envelope(
                """{"id":"w-1","placeId":"p-1","userName":"Aziz","serviceName":"Soch olish",
                   "status":"WAITING","queuePosition":3,"estimatedWaitMinutes":25,
                   "counterTime":"14:30","barberNote":"Kutib turing",
                   "createdAt":"2026-08-31T10:15:00"}""",
            ),
        )

        val request = (repository().sendServiceOrder("p-1", form()) as ApiResult.Success).data

        assertEquals("w-1", request.id)
        assertEquals(ServiceRequestStatus.Waiting, request.status)
        assertEquals(3, request.queuePosition)
        assertEquals(25, request.estimatedWaitMinutes)
        assertEquals("14:30", request.counterTime)
        assertEquals("Kutib turing", request.barberNote)
        // Дата без зоны — Jackson на бэкенде отдаёт именно её (issue #53).
        assertEquals(Instant.parse("2026-08-31T10:15:00Z"), request.createdAt)
    }

    @Test
    fun `zero queue position is not a position`() = runTest {
        server.enqueue(
            envelope("""{"id":"w-1","status":"PENDING","queuePosition":0,"estimatedWaitMinutes":0}"""),
        )

        val request = (repository().sendServiceOrder("p-1", form()) as ApiResult.Success).data

        // Ноль означает «сервер её ещё не считал»: «вы 0-й в очереди» — не ответ.
        assertNull(request.queuePosition)
        assertNull(request.estimatedWaitMinutes)
    }

    @Test
    fun `request without id is a failure, not a silent success`() = runTest {
        server.enqueue(envelope("""{"status":"PENDING"}"""))

        val result = repository().sendServiceOrder("p-1", form())

        assertEquals(ApiError.Serialization, (result as ApiResult.Failure).error)
    }

    @Test
    fun `envelope with success false is a failure`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"PLACE_CLOSED","message":"Joy yopiq"}}""",
                ),
        )

        val failure = (repository().sendServiceOrder("p-1", form()) as ApiResult.Failure).failure

        assertEquals(ApiError.Business("PLACE_CLOSED"), failure.error)
        assertEquals("Joy yopiq", failure.serverMessage)
    }

    @Test
    fun `missing offer is an empty form, not an error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = repository().myOffer()

        // «Анкеты ещё нет» — обычный первый вход в форму. Экран ошибки здесь
        // закрыл бы вход в неё навсегда.
        assertNull((result as ApiResult.Success).data)
    }

    @Test
    fun `missing offer reported in the envelope is empty too`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody("""{"success":false,"error":{"code":"FREELANCER_NOT_FOUND"}}"""),
        )

        assertNull((repository().myOffer() as ApiResult.Success).data)
    }

    @Test
    fun `other errors of the offer stay errors`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        assertEquals(ApiError.Unauthorized, (repository().myOffer() as ApiResult.Failure).error)
    }

    @Test
    fun `offer is read with the phone in the form the field understands`() = runTest {
        server.enqueue(
            envelope(
                """{"id":"f-1","name":"Jahongir","profession":"Sartarosh","city":"Toshkent",
                   "phone":"+998901234567","hourlyRate":80000,"experienceYears":10,
                   "isAvailable":false,"ratingAvg":4.8,"ratingCount":12}""",
            ),
        )

        val offer = (repository().myOffer() as ApiResult.Success).data!!

        assertEquals("/freelancers/me", server.takeRequest().path)
        assertEquals("Sartarosh", offer.profession)
        assertEquals(80_000L, offer.hourlyRateSum)
        assertEquals(10, offer.experienceYears)
        assertEquals(false, offer.isAvailable)
        assertEquals(4.8, offer.ratingAverage!!, 0.001)
        assertEquals(12, offer.ratingCount)
        assertTrue(offer.phone, offer.phone.endsWith("90 123 45 67"))
    }

    @Test
    fun `offer without availability flag is treated as accepting orders`() = runTest {
        server.enqueue(envelope("""{"id":"f-1","name":"Jahongir"}"""))

        assertTrue((repository().myOffer() as ApiResult.Success).data!!.isAvailable)
    }

    @Test
    fun `saving the offer sends trimmed fields and the phone in e164`() = runTest {
        server.enqueue(envelope("""{"id":"f-1","name":"Jahongir"}"""))

        repository().saveOffer(
            ServiceOfferForm(
                name = " Jahongir ",
                profession = "Sartarosh",
                city = "Toshkent",
                bio = "  ",
                phoneDigits = "901234567",
                hourlyRate = "80000",
                experienceYears = "10",
            ),
        )

        val request = server.takeRequest()
        assertEquals("/freelancers/me", request.path)
        assertEquals("POST", request.method)
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("\"name\":\"Jahongir\""))
        assertTrue(body, body.contains("\"phone\":\"+998901234567\""))
        assertTrue(body, body.contains("\"hourlyRate\":80000"))
        assertTrue(body, body.contains("\"experienceYears\":10"))
        // Пустое поле не отправляется вовсе: «стереть» и «не менять» для
        // сервера должны выглядеть по-разному.
        assertTrue(body, !body.contains("\"bio\""))
    }

    @Test
    fun `availability toggle needs no payload`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody("""{"success":true}"""),
        )

        val result = repository().toggleAvailability()

        val request = server.takeRequest()
        assertEquals("/freelancers/me/toggle-availability", request.path)
        assertEquals("PUT", request.method)
        // `data` у пустого конверта `null` и при успехе — разбор на этом
        // спотыкаться не должен.
        assertTrue(result is ApiResult.Success)
    }

    private fun form() = ServiceOrderForm(customerName = "Aziz", serviceName = "Soch olish")

    private fun repository() = DefaultServicesRepository(
        NetworkFactory
            .retrofit(
                server.url("/").toString(),
                NetworkFactory.clientBuilder().build(),
                NetworkFactory.converterFactory(NetworkFactory.json()),
            )
            .create(ServicesApi::class.java),
        PhoneNumberValidator(),
    )

    private fun envelope(data: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody("""{"success":true,"data":$data}""")
}
