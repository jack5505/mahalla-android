package uz.mahalla.feature.freelancer.data

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
import uz.mahalla.feature.freelancer.domain.FreelancerAnketaForm
import uz.mahalla.feature.freelancer.domain.FreelancerOrderStatus
import uz.mahalla.feature.freelancer.domain.FreelancerServiceDraft
import uz.mahalla.feature.onboarding.domain.PhoneNumberValidator

/**
 * Кабинет мастера (issue #190) на настоящем сетевом стеке ([NetworkFactory] +
 * [MockWebServer]).
 *
 * Контракт этих восьми ручек **не подтверждён живым стендом** — ни в этом
 * прогоне, ни на момент issue не было `CONTRACT_REFRESH_TOKEN` (см. KDoc
 * [FreelancerCabinetApi] и риск в issue #190). Эти тесты фиксируют текущее,
 * задокументированное как черновик, поведение клиента — а не подтверждают
 * бэкенд; расхождение со стендом будет отдельной правкой, а не сюрпризом.
 *
 * Главный риск задачи закреплён явно: и `404`, и `200` с пустым `data`
 * сводятся к одному и тому же «анкеты нет».
 */
class FreelancerCabinetRepositoryTest {

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
    fun `me returns null on 404 - no anketa yet`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody("""{"success":false,"error":{"code":"NOT_FOUND","message":"Profil topilmadi"}}"""),
        )

        val result = repository().me()

        assertEquals(ApiResult.Success(null), result)
    }

    /** Риск issue #190: бэкенд может ответить и так вместо `404`. */
    @Test
    fun `me returns null on 200 with empty data`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody("""{"success":true,"data":null}"""),
        )

        val result = repository().me()

        assertEquals(ApiResult.Success(null), result)
    }

    @Test
    fun `me returns the profile when it exists`() = runTest {
        server.enqueue(envelope("""{"id":"f-1","name":"Aziz","profession":"Santexnik"}"""))

        val result = repository().me()

        assertEquals("/freelancers/me", server.takeRequest().path)
        val freelancer = (result as ApiResult.Success).data
        assertEquals("f-1", freelancer?.id)
        assertEquals("Santexnik", freelancer?.profession)
    }

    /** Другие отказы (не 404) остаются отказами — не сводятся к «анкеты нет». */
    @Test
    fun `me keeps other failures as failures`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody("""{"success":false,"error":{"code":"UNAUTHORIZED"}}"""),
        )

        val result = repository().me()

        assertEquals(ApiError.Unauthorized, (result as ApiResult.Failure).error)
    }

    @Test
    fun `submit profile sends required fields and omits blank optional ones`() = runTest {
        server.enqueue(envelope("""{"id":"f-1","name":"Aziz","profession":"Santexnik"}"""))

        val result = repository().submitProfile(
            FreelancerAnketaForm(name = " Aziz ", profession = " Santexnik "),
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/freelancers/me", request.path)
        assertEquals("""{"name":"Aziz","profession":"Santexnik"}""", request.body.readUtf8())
        assertEquals("f-1", (result as ApiResult.Success).data.id)
    }

    @Test
    fun `submit profile converts the hourly rate from som to tiyin`() = runTest {
        server.enqueue(envelope("""{"id":"f-1","name":"Aziz","profession":"Santexnik"}"""))

        repository().submitProfile(
            FreelancerAnketaForm(
                name = "Aziz",
                profession = "Santexnik",
                phoneDigits = "901234567",
                hourlyRateText = "50000",
            ),
        )

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains(""""hourlyRate":5000000"""))
        assertTrue(body.contains(""""phone":"+998901234567""""))
    }

    @Test
    fun `submit profile without a profession never reaches the network`() = runTest {
        val result = repository().submitProfile(FreelancerAnketaForm(name = "Aziz"))

        assertEquals(
            ApiError.Business(FreelancerCabinetRepository.INVALID_FORM_CODE),
            (result as ApiResult.Failure).error,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `incoming orders are paged and parsed`() = runTest {
        server.enqueue(
            envelope(
                """{"content":[{"id":"o-1","serviceTitle":"Kran","status":"PENDING"}],
                   "page":0,"totalPages":1,"last":true}""",
            ),
        )

        val page = (repository().incomingOrders(page = 0) as ApiResult.Success).data

        assertEquals("/freelancers/me/orders?page=0&size=20", server.takeRequest().path)
        assertEquals(listOf("o-1"), page.items.map { it.id })
        assertFalse(page.hasMore)
    }

    /**
     * Свои услуги идут по каталожному пути с собственным `id`, но разбираются
     * правильной схемой ([FreelancerServiceDto]) — и, в отличие от каталога
     * (issue #216), не роняют выключенные: мастеру нужно видеть и включить их
     * обратно.
     */
    @Test
    fun `own services keep the inactive ones and use the fixed schema`() = runTest {
        server.enqueue(
            envelope(
                """[{"id":"s-1","title":"Kran","priceAmount":15000000,
                   "durationMinutes":60,"isActive":true},
                   {"id":"s-2","title":"Eski","priceAmount":5000000,"isActive":false}]""",
            ),
        )

        val services = (repository().services("f-1") as ApiResult.Success).data

        assertEquals("/freelancers/f-1/services", server.takeRequest().path)
        assertEquals(listOf("s-1", "s-2"), services.map { it.id })
        assertEquals("Kran", services.first().title)
        assertEquals(150_000L, services.first().priceSum)
        assertFalse(services.last().isActive)
    }

    @Test
    fun `add service converts the price to tiyin`() = runTest {
        server.enqueue(
            envelope("""{"id":"s-1","title":"Kran","priceAmount":8000000,"durationMinutes":60}"""),
        )

        val result = repository().addService(
            FreelancerServiceDraft(title = "Kran", priceText = "80000", durationText = "60"),
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/freelancers/me/services", request.path)
        assertTrue(request.body.readUtf8().contains(""""priceAmount":8000000"""))
        assertEquals(80_000L, (result as ApiResult.Success).data.priceSum)
    }

    @Test
    fun `add service without a price never reaches the network`() = runTest {
        val result = repository().addService(FreelancerServiceDraft(title = "Kran", durationText = "60"))

        assertTrue(result is ApiResult.Failure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `update service puts to the service id`() = runTest {
        server.enqueue(
            envelope("""{"id":"s-1","title":"Kran 2","priceAmount":9000000,"durationMinutes":45}"""),
        )

        val result = repository().updateService(
            "s-1",
            FreelancerServiceDraft(title = "Kran 2", priceText = "90000", durationText = "45"),
        )

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/freelancers/me/services/s-1", request.path)
        assertEquals("Kran 2", (result as ApiResult.Success).data.title)
    }

    @Test
    fun `delete service sends the request to the service id`() = runTest {
        server.enqueue(envelope("{}"))

        val result = repository().deleteService("s-1")

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/freelancers/me/services/s-1", request.path)
        assertTrue(result is ApiResult.Success)
    }

    @Test
    fun `toggle availability sends no body`() = runTest {
        server.enqueue(envelope("false"))

        val result = repository().toggleAvailability(current = true)

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/freelancers/me/toggle-availability", request.path)
        assertEquals(0L, request.bodySize)
        assertEquals(false, (result as ApiResult.Success).data)
    }

    /** `data` пуст — новое значение неизвестно, флаг просто переворачивается. */
    @Test
    fun `toggle availability falls back to flipping when data is missing`() = runTest {
        server.enqueue(envelope("null"))

        val result = repository().toggleAvailability(current = true)

        assertEquals(false, (result as ApiResult.Success).data)
    }

    @Test
    fun `order status update sends the enum value`() = runTest {
        server.enqueue(envelope("""{"id":"o-1","status":"ACCEPTED"}"""))

        val result = repository().updateOrderStatus("o-1", FreelancerOrderStatus.Accepted)

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/freelancers/orders/o-1/status", request.path)
        assertEquals("""{"status":"ACCEPTED"}""", request.body.readUtf8())
        assertEquals(FreelancerOrderStatus.Accepted, (result as ApiResult.Success).data.status)
    }

    private fun repository() = DefaultFreelancerCabinetRepository(
        api = NetworkFactory
            .retrofit(
                server.url("/").toString(),
                NetworkFactory.clientBuilder().build(),
                NetworkFactory.converterFactory(NetworkFactory.json()),
            )
            .create(FreelancerCabinetApi::class.java),
        phoneValidator = PhoneNumberValidator(),
    )

    private fun envelope(data: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody("""{"success":true,"data":$data}""")
}
