package uz.mahalla.feature.discovery.data

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
import uz.mahalla.data.location.DeviceLocation
import uz.mahalla.data.location.RequestLocationProvider
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.feature.discovery.domain.DiscoveryFilters
import uz.mahalla.feature.discovery.domain.Place
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.place.domain.ReviewDraft
import uz.mahalla.testutil.FakePlaceDao
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Репозиторий каталога (эпик 4, контракт бэкенда — issue #53): сеть с
 * фоллбэком на Room.
 *
 * Сеть — настоящая, на [MockWebServer] и том же [NetworkFactory], что в
 * проде: подмена Retrofit фейком не поймала бы ни ошибку в пути запроса, ни
 * несовпадение схемы JSON. База подменена фейком — здесь проверяются правила
 * фоллбэка, а не SQL (DAO покрыт в `MahallaDatabaseTest`).
 */
class CatalogRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var dao: FakePlaceDao

    private val clock: Clock = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC)

    private val location = object : RequestLocationProvider {
        override suspend fun current() = DeviceLocation(latitude = 41.3111, longitude = 69.2797)
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dao = FakePlaceDao()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `an empty query goes to nearby with the coordinates`() = runTest {
        // Ровно то, чего не хватало в issue #53: без lat/lng бэкенд отвечает
        // 403 GEO_PERMISSION_REQUIRED, а `GET places` у него и вовсе нет.
        server.enqueue(json(NEARBY_BODY))

        repository().places(DiscoveryFilters())

        val path = server.takeRequest().path.orEmpty()
        assertTrue(path, path.startsWith("/places/nearby?"))
        assertTrue(path, path.contains("lat=41.3111"))
        assertTrue(path, path.contains("lng=69.2797"))
        assertTrue(path, path.contains("radiusMeters=${CatalogApi.DEFAULT_RADIUS_METERS}"))
    }

    @Test
    fun `successful page is mapped and cached`() = runTest {
        server.enqueue(json(NEARBY_BODY))

        val result = repository().places(DiscoveryFilters())

        val page = (result as ApiResult.Success).data
        assertEquals(listOf("near", "far"), page.items.map(Place::id))
        assertFalse(page.fromCache)
        assertFalse("сервер отдаёт всё одним списком", page.hasMore)
        assertEquals(2, dao.current().size)
    }

    @Test
    fun `the distance filter becomes the search radius`() = runTest {
        server.enqueue(json(NEARBY_BODY))

        repository().places(DiscoveryFilters(maxDistanceMeters = 1_000))

        assertTrue(server.takeRequest().path.orEmpty().contains("radiusMeters=1000"))
    }

    @Test
    fun `a category is sent in the value of the backend enum`() = runTest {
        server.enqueue(json(NEARBY_BODY))

        repository().places(DiscoveryFilters(categories = setOf(PlaceCategory.Playground)))

        // «playground» бэкенд не знает: у него эта категория называется GAMING.
        assertTrue(server.takeRequest().path.orEmpty().contains("category=GAMING"))
    }

    @Test
    fun `a non-empty query goes to the search index`() = runTest {
        // Поиск бэкенда смотрит описание и город, а не только название —
        // подменять его выдачей «рядом» значит терять находки.
        server.enqueue(json(SEARCH_BODY))

        val result = repository().places(DiscoveryFilters(query = "  osh  "))

        val path = server.takeRequest().path.orEmpty()
        assertTrue(path, path.startsWith("/search?"))
        assertTrue(path, path.contains("query=osh"))
        assertEquals(listOf("s-1"), (result as ApiResult.Success).data.items.map(Place::id))
    }

    @Test
    fun `a search hit gets its distance measured locally`() = runTest {
        server.enqueue(json(SEARCH_BODY))

        val result = repository().places(DiscoveryFilters(query = "osh"))

        val place = (result as ApiResult.Success).data.items.single()
        assertTrue("${place.distanceMeters} м", place.distanceMeters in 400..600)
    }

    @Test
    fun `server results are not re-filtered by the query`() = runTest {
        // Сервер ищет по описанию и городу, matchesQuery — только по названию
        // и адресу. Прогнать ответ через полный фильтр значит показать
        // «ничего не найдено» при непустом ответе.
        server.enqueue(json(SEARCH_BODY))

        val result = repository().places(DiscoveryFilters(query = "shashlik"))

        assertEquals(1, (result as ApiResult.Success).data.items.size)
    }

    @Test
    fun `server results are not re-filtered by the rating threshold`() = runTest {
        // Числа отзывов в кратком ответе нет, hasRating = false — локальный
        // порог вырезал бы всю выдачу.
        server.enqueue(json(SEARCH_BODY))

        val result = repository().places(DiscoveryFilters(query = "osh", minRating = 4.0))

        assertEquals(1, (result as ApiResult.Success).data.items.size)
    }

    @Test
    fun `only the categories that did not fit the request are cut locally`() = runTest {
        // В запрос уходит одна категория; если сервер прислал чужую — она
        // лишняя. Категория, которой ещё нет в приложении (Other), остаётся:
        // иначе новые разделы каталога были бы невидимы до следующего релиза.
        server.enqueue(json(MIXED_BODY))

        val result = repository().places(
            DiscoveryFilters(categories = setOf(PlaceCategory.Pharmacy)),
        )

        val ids = (result as ApiResult.Success).data.items.map(Place::id)
        assertEquals(listOf("pharmacy", "unknown"), ids)
    }

    @Test
    fun `an envelope with success false is a failure, not an empty screen`() = runTest {
        // 200 с success:false — это отказ бэкенда, а пустой список означал бы
        // «рядом ничего нет».
        server.enqueue(json(ENVELOPE_FAILURE_BODY))

        val result = repository().places(DiscoveryFilters())

        val failure = (result as ApiResult.Failure).failure
        assertEquals("GEO_PERMISSION_REQUIRED", failure.server?.code)
    }

    @Test
    fun `first page falls back to the cache when the network fails`() = runTest {
        dao.seed(listOf(entity("cached", distanceMeters = 200)))
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository().places(DiscoveryFilters())

        val page = (result as ApiResult.Success).data
        assertEquals(listOf("cached"), page.items.map(Place::id))
        assertTrue("экран обязан знать, что данные старые", page.fromCache)
        assertFalse("догружать кэш нечем", page.hasMore)
    }

    @Test
    fun `cache fallback respects the active filters`() = runTest {
        dao.seed(
            listOf(
                entity("food", category = "FOOD"),
                entity("pharmacy", category = "PHARMACY"),
            ),
        )
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository().places(
            DiscoveryFilters(categories = setOf(PlaceCategory.Pharmacy)),
        )

        assertEquals(listOf("pharmacy"), (result as ApiResult.Success).data.items.map(Place::id))
    }

    @Test
    fun `empty cache is a plain error, not an empty list`() = runTest {
        // Пустой список означал бы «рядом ничего нет» — экран показал бы
        // «пусто» вместо кнопки «повторить».
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository().places(DiscoveryFilters())

        val error = (result as ApiResult.Failure).error
        assertEquals(500, (error as ApiError.Http).code)
    }

    @Test
    fun `cache that does not match the filters is an error too`() = runTest {
        dao.seed(listOf(entity("food", category = "FOOD")))
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository().places(
            DiscoveryFilters(categories = setOf(PlaceCategory.Cinema)),
        )

        assertTrue(result is ApiResult.Failure)
    }

    @Test
    fun `later pages are not requested at all`() = runTest {
        // Пагинации у бэкенда нет: сходить за той же первой страницей значило
        // бы дописать её в список второй раз.
        val result = repository().places(DiscoveryFilters(), page = 1)

        val page = (result as ApiResult.Success).data
        assertTrue(page.items.isEmpty())
        assertFalse(page.hasMore)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a filtered search does not overwrite the offline catalog`() = runTest {
        // Иначе офлайн-главная показывала бы то, что человек искал вчера,
        // вместо всего, что рядом.
        dao.seed(listOf(entity("old")))
        server.enqueue(json(SEARCH_BODY))

        repository().places(DiscoveryFilters(query = "osh"))

        assertEquals(listOf("old"), dao.current().map { it.id })
    }

    @Test
    fun `caching drops entries older than the ttl`() = runTest {
        server.enqueue(json(NEARBY_BODY))

        repository().places(DiscoveryFilters())

        assertEquals(NOW - CACHE_TTL_SECONDS, dao.deleteStaleThreshold)
    }

    @Test
    fun `details are loaded together with reviews`() = runTest {
        server.enqueue(json(DETAILS_BODY))
        server.enqueue(json(REVIEWS_BODY))

        val result = repository().placeDetails("p-1")

        val details = (result as ApiResult.Success).data
        assertEquals("Osh markazi", details.place.name)
        assertEquals("Eng mazali osh", details.description)
        assertEquals("+998901234567", details.contacts.phone)
        assertEquals(listOf("r-1"), details.reviews.map { it.id })
        assertEquals("Ali", details.reviews.single().author)
        assertFalse(details.fromCache)
        assertEquals("/places/p-1", server.takeRequest().path)
        assertTrue(server.takeRequest().path.orEmpty().startsWith("/reviews/places/p-1"))
    }

    @Test
    fun `the card is cached whole`() = runTest {
        // Открытая офлайн, она иначе показывала бы одно название.
        server.enqueue(json(DETAILS_BODY))
        server.enqueue(json(REVIEWS_BODY))

        repository().placeDetails("p-1")

        val cached = dao.byId("p-1")!!
        assertEquals("Eng mazali osh", cached.description)
        assertEquals("+998901234567", cached.phone)
    }

    @Test
    fun `broken reviews do not break the card`() = runTest {
        // Ради карточки человек сюда и пришёл — терять её из-за отзывов нельзя.
        server.enqueue(json(DETAILS_BODY))
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository().placeDetails("p-1")

        assertTrue((result as ApiResult.Success).data.reviews.isEmpty())
    }

    @Test
    fun `details fall back to the cached place`() = runTest {
        dao.seed(listOf(entity("p-1", name = "Osh markazi", phone = "+998901234567")))
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository().placeDetails("p-1")

        val details = (result as ApiResult.Success).data
        assertEquals("Osh markazi", details.place.name)
        assertEquals("+998901234567", details.contacts.phone)
        assertTrue(details.fromCache)
        assertTrue("часы устаревают быстрее всего — их не кэшируем", details.hours.isEmpty())
    }

    @Test
    fun `a deleted place is not resurrected from the cache`() = runTest {
        // 404 — это не «не доехало», а «места больше нет». Показать копию из
        // Room значит отправить человека по адресу, которого не существует.
        dao.seed(listOf(entity("p-1")))
        server.enqueue(MockResponse().setResponseCode(404))

        val result = repository().placeDetails("p-1")

        assertEquals(ApiError.NotFound, (result as ApiResult.Failure).error)
        assertNull("запись должна уйти и из офлайн-выдачи", dao.byId("p-1"))
    }

    @Test
    fun `a place that just did not arrive still comes from the cache`() = runTest {
        dao.seed(listOf(entity("p-1", name = "Osh markazi")))
        server.enqueue(MockResponse().setResponseCode(503))

        val result = repository().placeDetails("p-1")

        assertTrue((result as ApiResult.Success).data.fromCache)
    }

    @Test
    fun `details without a cached copy report the error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = repository().placeDetails("p-1")

        assertEquals(ApiError.NotFound, (result as ApiResult.Failure).error)
        assertNull(dao.byId("p-1"))
    }

    // --- Отзывы: оставить и удалить (issue #76) ---

    @Test
    fun `a review goes to POST reviews with the place, the rating and the text`() = runTest {
        server.enqueue(json(ENVELOPE_OK_BODY))

        val result = repository().addReview("p-1", ReviewDraft(rating = 5, text = "  Zo'r  "))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/reviews", request.path)
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("\"placeId\":\"p-1\""))
        assertTrue(body, body.contains("\"rating\":5"))
        assertTrue("текст обрезан по краям: $body", body.contains("\"text\":\"Zo'r\""))
        assertTrue(result is ApiResult.Success)
    }

    @Test
    fun `a review without text goes without the field, not with null`() = runTest {
        // `text` у бэкенда необязателен, а `"text":null` — лишний повод для
        // спора с валидатором на той стороне.
        server.enqueue(json(ENVELOPE_OK_BODY))

        repository().addReview("p-1", ReviewDraft(rating = 4))

        val body = server.takeRequest().body.readUtf8()
        assertFalse(body, body.contains("text"))
    }

    @Test
    fun `an unfinished draft never reaches the network`() = runTest {
        // Оценки нет — 400 от сервера сказал бы то же самое, но экран успел бы
        // показать спиннер и ожидание.
        val result = repository().addReview("p-1", ReviewDraft(text = "Zo'r"))

        assertEquals(
            ApiError.Business(ReviewDraft.INVALID_CODE),
            (result as ApiResult.Failure).error,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a rejected review carries the message of the backend`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(REVIEW_DUPLICATE_BODY),
        )

        val result = repository().addReview("p-1", ReviewDraft(rating = 5))

        val failure = (result as ApiResult.Failure).failure
        assertEquals("Siz bu joyga allaqachon sharh qoldirgansiz", failure.serverMessage)
    }

    @Test
    fun `a 2xx envelope with success false is a failure, not a sent review`() = runTest {
        // Иначе форма закрылась бы, а отзыва не появилось: HTTP-код тут 200.
        server.enqueue(json(REVIEW_ENVELOPE_FAILURE_BODY))

        val result = repository().addReview("p-1", ReviewDraft(rating = 5))

        assertEquals(ApiError.Business("REVIEW_NOT_ALLOWED"), (result as ApiResult.Failure).error)
    }

    @Test
    fun `deleting a review goes to DELETE reviews by id`() = runTest {
        server.enqueue(json(ENVELOPE_OK_BODY))

        val result = repository().deleteReview("r-1")

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/reviews/r-1", request.path)
        assertTrue(result is ApiResult.Success)
    }

    @Test
    fun `deleting someone else review is reported, not swallowed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        val result = repository().deleteReview("r-1")

        assertEquals(ApiError.Forbidden, (result as ApiResult.Failure).error)
    }

    @Test
    fun `the author id of a review reaches the domain`() = runTest {
        // Единственный признак «это мой отзыв»: отдельного флага бэкенд не даёт.
        server.enqueue(json(DETAILS_BODY))
        server.enqueue(json(REVIEWS_BODY))

        val result = repository().placeDetails("p-1")

        assertEquals("u-1", (result as ApiResult.Success).data.reviews.single().authorId)
    }

    private fun repository(): DefaultCatalogRepository {
        val api = NetworkFactory
            .retrofit(
                server.url("/").toString(),
                NetworkFactory.clientBuilder().build(),
                NetworkFactory.converterFactory(NetworkFactory.json()),
            )
            .create(CatalogApi::class.java)
        return DefaultCatalogRepository(api, dao, location, clock)
    }

    private fun entity(
        id: String,
        name: String = "Place $id",
        category: String = "FOOD",
        distanceMeters: Int = 500,
        phone: String? = null,
    ) = PlaceSummaryDto(
        id = id,
        name = name,
        category = category,
        ratingAvg = 4.5,
        ratingCount = 10,
        distanceMeters = distanceMeters.toDouble(),
        isAvailable = true,
    ).toDomain().toEntity(NOW, phone = phone)

    private fun json(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody(body)

    private companion object {
        const val NOW = 1_774_000_000L
        const val CACHE_TTL_SECONDS = 7L * 24 * 60 * 60

        const val NEARBY_BODY = """
            {"success":true,"data":[
              {"id":"far","name":"Far","category":"FOOD","ratingAvg":4.1,"distanceMeters":900.0,"isAvailable":true},
              {"id":"near","name":"Near","category":"FOOD","ratingAvg":4.9,"distanceMeters":100.0,"isAvailable":true}
            ]}
        """

        const val MIXED_BODY = """
            {"success":true,"data":[
              {"id":"food","name":"Food","category":"FOOD","distanceMeters":300.0},
              {"id":"pharmacy","name":"Pharmacy","category":"PHARMACY","distanceMeters":100.0},
              {"id":"unknown","name":"Bakery","category":"BAKERY","distanceMeters":200.0}
            ]}
        """

        const val SEARCH_BODY = """
            {"success":true,"data":[
              {"id":"s-1","name":"Osh markazi","category":"FOOD","description":"Shashlik ham bor",
               "city":"Toshkent","lat":41.3157,"lng":69.2797,"ratingAvg":4.6,"isActive":true}
            ]}
        """

        const val ENVELOPE_FAILURE_BODY = """
            {"success":false,"error":{"code":"GEO_PERMISSION_REQUIRED",
             "message":"Joylashuv ruxsatini yoqing"}}
        """

        const val DETAILS_BODY = """
            {"success":true,"data":{"id":"p-1","name":"Osh markazi","category":"FOOD",
             "description":"Eng mazali osh","address":"Amir Temur 1","lat":41.31,"lng":69.28,
             "phone":"+998901234567","isAvailable":true,"ratingAvg":4.6,"ratingCount":42,
             "coverUrl":"cover.jpg"}}
        """

        const val REVIEWS_BODY = """
            {"success":true,"data":{"content":[{"id":"r-1","userId":"u-1","userName":"Ali",
             "rating":5,"text":"Zo'r","createdAt":"2026-08-25T10:15:30Z"}],
             "page":0,"totalPages":1,"totalElements":1,"last":true}}
        """

        /** Конверт без полезной нагрузки: так отвечают `POST`/`DELETE` отзыва. */
        const val ENVELOPE_OK_BODY = """{"success":true,"data":{}}"""

        const val REVIEW_ENVELOPE_FAILURE_BODY = """
            {"success":false,"error":{"code":"REVIEW_NOT_ALLOWED",
             "message":"Sharh qoldirish mumkin emas"}}
        """

        const val REVIEW_DUPLICATE_BODY = """
            {"success":false,"error":{"code":"REVIEW_DUPLICATE",
             "message":"Siz bu joyga allaqachon sharh qoldirgansiz"}}
        """
    }
}
