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
import uz.mahalla.feature.freelancer.domain.FreelancerOrderDraft
import uz.mahalla.feature.freelancer.domain.FreelancerOrderStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Мастера (issue #107) на настоящем сетевом стеке ([NetworkFactory] +
 * [MockWebServer]): подмена Retrofit фейком не поймала бы ни ошибку в пути
 * запроса, ни несовпадение схемы JSON.
 *
 * Контракт снят со стенда 2026-09-04 и проверен живыми запросами: каталог,
 * профиль и услуги анонимны (`200`; каталог сегодня пуст), заказ и «мои
 * заказы» требуют Bearer (`401` приходит **до** валидации тела). Тело
 * `POST freelancers/{id}/orders` поэтому закрепляется тестом — правка после
 * проверки под токеном будет видна одной строкой.
 */
class FreelancerRepositoryTest {

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
    fun `catalog sends paging and the profession filter`() = runTest {
        server.enqueue(
            envelope(
                """{"content":[{"id":"f-1","name":"Aziz Karimov","profession":"Santexnik",
                   "city":"Toshkent","phone":"+998901234567","hourlyRate":80000,
                   "experienceYears":7,"isAvailable":true,"ratingAvg":4.8,"ratingCount":12}],
                   "page":0,"totalPages":3,"last":false}""",
            ),
        )

        val page = (
            repository().freelancers(profession = " santexnik ", page = 1) as ApiResult.Success
            ).data

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/freelancers?profession=santexnik&page=1&size=20", request.path)
        assertEquals(1, page.items.size)
        val freelancer = page.items.first()
        assertEquals("Aziz Karimov", freelancer.name)
        assertEquals("Santexnik", freelancer.profession)
        assertEquals(80_000L, freelancer.hourlyRateSum)
        assertEquals(7, freelancer.experienceYears)
        assertEquals(12, freelancer.ratingCount)
        assertTrue(freelancer.isAvailable)
        assertTrue(page.hasMore)
    }

    /**
     * Пустой фильтр — это отсутствие параметра, а не `profession=`: пустую
     * строку бэкенд вправе счесть искомой специальностью.
     */
    @Test
    fun `blank profession is not sent at all`() = runTest {
        server.enqueue(envelope("""{"content":[],"last":true}"""))

        repository().freelancers(profession = "   ")

        assertEquals("/freelancers?page=0&size=20", server.takeRequest().path)
    }

    /** Пустой каталог — это ответ стенда сегодня, и он не ошибка. */
    @Test
    fun `empty catalog is a success`() = runTest {
        server.enqueue(envelope("""{"content":[],"page":0,"totalPages":0,"last":true}"""))

        val page = (repository().freelancers() as ApiResult.Success).data

        assertTrue(page.items.isEmpty())
        assertFalse(page.hasMore)
    }

    /** Мастера без `id` ни открыть, ни заказать: `id` идёт в путь запроса. */
    @Test
    fun `freelancer without id is dropped and the rest survives`() = runTest {
        server.enqueue(
            envelope(
                """{"content":[{"name":"Ismsiz"},{"id":"  "},
                   {"id":"f-2","hourlyRate":-5,"ratingAvg":-1.0,"experienceYears":0}]}""",
            ),
        )

        val items = (repository().freelancers() as ApiResult.Success).data.items

        assertEquals(listOf("f-2"), items.map { it.id })
        // Отрицательные значения — мусор, а не скидка и не антирейтинг.
        assertEquals(0L, items.first().hourlyRateSum)
        assertEquals(0.0, items.first().ratingAvg, 0.0)
        assertNull(items.first().experienceYears)
        assertEquals("", items.first().name)
    }

    /**
     * Jackson сериализует `boolean isAvailable` то так, то так — ошибка здесь
     * показала бы занятыми всех мастеров подряд.
     */
    @Test
    fun `availability is read under both field names`() = runTest {
        server.enqueue(
            envelope(
                """{"content":[{"id":"f-1","available":false},
                   {"id":"f-2","isAvailable":false},{"id":"f-3"}]}""",
            ),
        )

        val items = (repository().freelancers() as ApiResult.Success).data.items

        assertEquals(listOf(false, false, true), items.map { it.isAvailable })
    }

    /** `last` нет — считаем по страницам; молчание о страницах останавливает. */
    @Test
    fun `has more falls back to page counters`() = runTest {
        server.enqueue(envelope("""{"content":[{"id":"f-1"}],"page":0,"totalPages":2}"""))
        server.enqueue(envelope("""{"content":[{"id":"f-1"}]}"""))

        val repository = repository()

        assertTrue((repository.freelancers() as ApiResult.Success).data.hasMore)
        assertFalse((repository.freelancers() as ApiResult.Success).data.hasMore)
    }

    @Test
    fun `profile is requested by id`() = runTest {
        server.enqueue(
            envelope("""{"id":"f-1","name":"Aziz","bio":"Tajribali","isAvailable":false}"""),
        )

        val freelancer = (repository().freelancer("f-1") as ApiResult.Success).data

        assertEquals("/freelancers/f-1", server.takeRequest().path)
        assertEquals("Aziz", freelancer.name)
        assertEquals("Tajribali", freelancer.bio)
        assertFalse(freelancer.isAvailable)
    }

    /**
     * Профиль без `id` — отказ: заказывать у мастера, которого нечем назвать в
     * пути запроса, невозможно, и лучше сказать об этом сразу, чем показать
     * экран с кнопкой, которая не сработает.
     */
    @Test
    fun `profile without id is a failure`() = runTest {
        server.enqueue(envelope("""{"name":"Ismsiz"}"""))

        val result = repository().freelancer("f-1")

        assertEquals(ApiError.Serialization, (result as ApiResult.Failure).error)
    }

    /** Фактический ответ стенда на неизвестного мастера. */
    @Test
    fun `unknown freelancer keeps the server text`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"NOT_FOUND",
                       "message":"Profil topilmadi"}}""",
                ),
        )

        val result = repository().freelancer("f-1")

        assertEquals(ApiError.NotFound, (result as ApiResult.Failure).error)
        assertEquals("Profil topilmadi", result.failure.server?.message)
    }

    /**
     * Услуги мастера приезжают той же схемой `ServiceResponse`, что и у
     * `barber-services` (issue #97) — DTO переиспользуется. Выключенные не
     * показываются: заказать их нельзя.
     */
    @Test
    fun `services drop the inactive ones`() = runTest {
        server.enqueue(
            envelope(
                """[{"id":"s-1","title":"Kran","priceAmount":150000,"durationMinutes":60,
                   "isActive":true},{"id":"s-2","title":"Eski","active":false},
                   {"title":"Idsiz"},{"id":"s-3","title":"Bayroqsiz"}]""",
            ),
        )

        val services = (repository().services("f-1") as ApiResult.Success).data

        assertEquals("/freelancers/f-1/services", server.takeRequest().path)
        // Молчание сервера о флаге — «услуга оказывается».
        assertEquals(listOf("s-1", "s-3"), services.map { it.id })
        assertEquals(150_000L, services.first().priceSum)
        assertEquals(60, services.first().durationMinutes)
    }

    @Test
    fun `order sends service time address and comment`() = runTest {
        server.enqueue(
            envelope("""{"id":"o-1","serviceTitle":"Kran","status":"PENDING"}"""),
        )

        val result = repository().order(
            freelancerId = "f-1",
            draft = FreelancerOrderDraft(
                serviceId = "s-1",
                date = LocalDate.of(2026, 9, 5),
                time = LocalTime.of(10, 30),
                address = " Chilonzor 7 ",
                comment = " Kran oqyapti ",
            ),
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/freelancers/f-1/orders", request.path)
        assertEquals(
            """{"serviceId":"s-1","scheduledAt":"2026-09-05T05:30:00Z",""" +
                """"address":"Chilonzor 7","comment":"Kran oqyapti"}""",
            request.body.readUtf8(),
        )
        val order = (result as ApiResult.Success).data
        assertEquals("o-1", order.id)
        assertEquals(FreelancerOrderStatus.Pending, order.status)
    }

    /**
     * «Как можно скорее» — обычный способ вызвать мастера: `scheduledAt`
     * тогда не уходит вовсе, а не уходит `null` (в `Json` проекта
     * `explicitNulls = false`).
     */
    @Test
    fun `asap order carries only the service`() = runTest {
        server.enqueue(envelope("""{"id":"o-1"}"""))

        repository().order(
            freelancerId = "f-1",
            draft = FreelancerOrderDraft(serviceId = "s-1", date = LocalDate.of(2026, 9, 5)),
        )

        assertEquals("""{"serviceId":"s-1"}""", server.takeRequest().body.readUtf8())
    }

    /**
     * Ответ без `id` отказом не считается: заказ создан, и увидеть его можно в
     * «моих заказах» — в отличие от талона очереди (issue #96), где читать
     * состояние нечем.
     */
    @Test
    fun `created order without id is still a success`() = runTest {
        server.enqueue(envelope("""{"serviceTitle":"Kran","status":"PENDING"}"""))

        val result = repository().order(
            freelancerId = "f-1",
            draft = FreelancerOrderDraft(serviceId = "s-1"),
        )

        assertEquals("", (result as ApiResult.Success).data.id)
        assertEquals("Kran", result.data.serviceTitle)
    }

    @Test
    fun `order without a service never reaches the network`() = runTest {
        val result = repository().order(freelancerId = "f-1", draft = FreelancerOrderDraft())

        assertEquals(
            ApiError.Business(FreelancerRepository.INVALID_REQUEST_CODE),
            (result as ApiResult.Failure).error,
        )
        assertEquals(0, server.requestCount)
    }

    /** Прошедшее время — тоже отказ до сети: сервер сказал бы то же самое. */
    @Test
    fun `past time never reaches the network`() = runTest {
        val result = repository().order(
            freelancerId = "f-1",
            draft = FreelancerOrderDraft(
                serviceId = "s-1",
                date = LocalDate.of(2026, 9, 4),
                // 09:00 UTC = 14:00 в Ташкенте, значит 09:00 уже прошло.
                time = LocalTime.of(9, 0),
            ),
        )

        assertEquals(
            ApiError.Business(FreelancerRepository.INVALID_REQUEST_CODE),
            (result as ApiResult.Failure).error,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `too long comment never reaches the network`() = runTest {
        val result = repository().order(
            freelancerId = "f-1",
            draft = FreelancerOrderDraft(
                serviceId = "s-1",
                comment = "a".repeat(FreelancerOrderDraft.MAX_COMMENT_LENGTH + 1),
            ),
        )

        assertTrue(result is ApiResult.Failure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `my orders are paged and parsed`() = runTest {
        server.enqueue(
            envelope(
                """{"content":[{"id":"o-1","serviceTitle":"Kran","priceAmount":150000,
                   "status":"ACCEPTED","scheduledAt":"2026-09-06T10:30:00",
                   "address":"Chilonzor 7","createdAt":"2026-09-04T09:00:00Z"},
                   {"serviceTitle":"Idsiz"}],"page":1,"totalPages":2,"last":true}""",
            ),
        )

        val page = (repository().myOrders(page = 1) as ApiResult.Success).data

        assertEquals("/freelancers/orders/my?page=1&size=20", server.takeRequest().path)
        // Заказ без `id` отброшен: в `LazyColumn` это дубликат ключа.
        assertEquals(listOf("o-1"), page.items.map { it.id })
        val order = page.items.first()
        assertEquals(FreelancerOrderStatus.Accepted, order.status)
        assertEquals(150_000L, order.priceSum)
        assertEquals("Chilonzor 7", order.address)
        // Jackson отдаёт `LocalDateTime` без зоны — иначе время пусто у всех.
        assertEquals(Instant.parse("2026-09-06T10:30:00Z"), order.scheduledAt)
        assertEquals(Instant.parse("2026-09-04T09:00:00Z"), order.createdAt)
        assertFalse(page.hasMore)
    }

    /** Незнакомый статус заказ не прячет: его меняет мастер из своего кабинета. */
    @Test
    fun `unknown status keeps the order in the list`() = runTest {
        server.enqueue(envelope("""{"content":[{"id":"o-1","status":"IN_PROGRESS"}]}"""))

        val page = (repository().myOrders() as ApiResult.Success).data

        assertEquals(1, page.items.size)
        assertEquals(FreelancerOrderStatus.Unknown, page.items.first().status)
    }

    /** 2xx с `success:false` — отказ, а не пустой экран (issue #42). */
    @Test
    fun `envelope failure becomes a business error with the server text`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"SERVICE_UNAVAILABLE",
                       "message":"Xizmat o'chirilgan"}}""",
                ),
        )

        val result = repository().order(
            freelancerId = "f-1",
            draft = FreelancerOrderDraft(serviceId = "s-1"),
        )

        assertEquals(
            ApiError.Business("SERVICE_UNAVAILABLE"),
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
                    """{"success":false,"error":{"code":"FREELANCER_BUSY",
                       "message":"Usta bu vaqtda band"}}""",
                ),
        )

        val result = repository().order(
            freelancerId = "f-1",
            draft = FreelancerOrderDraft(serviceId = "s-1"),
        )

        assertEquals("Usta bu vaqtda band", (result as ApiResult.Failure).failure.server?.message)
    }

    /** Фактический ответ стенда без токена — на нём и держится вход. */
    @Test
    fun `unauthorized my orders is a failure`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"UNAUTHORIZED",
                       "message":"Kirish uchun autentifikatsiya talab qilinadi"}}""",
                ),
        )

        val result = repository().myOrders()

        assertEquals(ApiError.Unauthorized, (result as ApiResult.Failure).error)
    }

    private fun repository() = DefaultFreelancerRepository(
        api = NetworkFactory
            .retrofit(
                server.url("/").toString(),
                NetworkFactory.clientBuilder().build(),
                NetworkFactory.converterFactory(NetworkFactory.json()),
            )
            .create(FreelancerApi::class.java),
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
    )

    private fun envelope(data: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody("""{"success":true,"data":$data}""")

    private companion object {
        /** 09:00 UTC = 14:00 в Ташкенте, 4 сентября. */
        val NOW: Instant = Instant.parse("2026-09-04T09:00:00Z")
    }
}
