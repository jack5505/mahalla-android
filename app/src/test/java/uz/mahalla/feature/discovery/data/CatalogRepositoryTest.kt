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
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.feature.discovery.domain.DiscoveryFilters
import uz.mahalla.feature.discovery.domain.Place
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.discovery.domain.PlaceSort
import uz.mahalla.testutil.FakePlaceDao
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Репозиторий каталога (эпик 4): сеть с фоллбэком на Room.
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
    fun `successful page is mapped and cached`() = runTest {
        server.enqueue(json(PAGE_BODY))

        val result = repository().places(DiscoveryFilters())

        val page = (result as ApiResult.Success).data
        assertEquals(listOf("near", "far"), page.items.map(Place::id))
        assertFalse(page.fromCache)
        assertTrue(page.hasMore)
        assertEquals(2, dao.current().size)
    }

    @Test
    fun `filters are passed to the server`() = runTest {
        server.enqueue(json(PAGE_BODY))

        repository().places(
            DiscoveryFilters(
                query = "  osh  ",
                categories = setOf(PlaceCategory.Pharmacy),
                maxDistanceMeters = 1_000,
                minRating = 4.0,
                openNowOnly = true,
                sort = PlaceSort.Rating,
            ),
        )

        val path = server.takeRequest().path.orEmpty()
        assertTrue(path, path.contains("category=pharmacy"))
        assertTrue(path, path.contains("q=osh"))
        assertTrue(path, path.contains("openNow=true"))
        assertTrue(path, path.contains("maxDistance=1000"))
        assertTrue(path, path.contains("sort=rating"))
    }

    @Test
    fun `open now is omitted when the filter is off`() = runTest {
        // Параметра быть не должно вовсе: `openNow=false` сервер понял бы как
        // «покажи только закрытые».
        server.enqueue(json(PAGE_BODY))

        repository().places(DiscoveryFilters())

        assertFalse(server.takeRequest().path.orEmpty().contains("openNow"))
    }

    @Test
    fun `server results are not re-filtered by the query`() = runTest {
        // Сервер ищет по описанию, меню и тегам, matchesQuery — только по
        // названию и адресу. Прогнать ответ через полный фильтр значит
        // показать «ничего не найдено» при непустом ответе, да ещё и с
        // hasMore = true, до которого потом не добраться.
        server.enqueue(json(PAGE_BODY))

        val result = repository().places(DiscoveryFilters(query = "osh"))

        val page = (result as ApiResult.Success).data
        assertEquals(listOf("near", "far"), page.items.map(Place::id))
    }

    @Test
    fun `server results are not re-filtered by the rating threshold`() = runTest {
        // reviewCount в кратком ответе нет, hasRating = false — локальный порог
        // вырезал бы всю выдачу.
        server.enqueue(json(PAGE_BODY))

        val result = repository().places(DiscoveryFilters(minRating = 4.0))

        assertEquals(2, (result as ApiResult.Success).data.items.size)
    }

    @Test
    fun `only the categories that did not fit the request are cut locally`() = runTest {
        // В запрос уходит одна категория; если сервер прислал чужую — она
        // лишняя. Категория, которой ещё нет в приложении (Other), остаётся:
        // иначе новые разделы каталога были бы невидимы до следующего релиза.
        server.enqueue(json(MIXED_PAGE_BODY))

        val result = repository().places(
            DiscoveryFilters(categories = setOf(PlaceCategory.Pharmacy)),
        )

        val ids = (result as ApiResult.Success).data.items.map(Place::id)
        assertEquals(listOf("pharmacy", "unknown"), ids)
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
                entity("food", category = "food"),
                entity("pharmacy", category = "pharmacy"),
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
        dao.seed(listOf(entity("food", category = "food")))
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository().places(
            DiscoveryFilters(categories = setOf(PlaceCategory.Cinema)),
        )

        assertTrue(result is ApiResult.Failure)
    }

    @Test
    fun `later pages are not served from the cache`() = runTest {
        // Дорисовать «хвост» списка из кэша значит смешать свежие и старые
        // данные в одном списке — пользователь их не различит.
        dao.seed(listOf(entity("cached")))
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository().places(DiscoveryFilters(), page = 1)

        assertTrue(result is ApiResult.Failure)
    }

    @Test
    fun `a filtered search does not overwrite the offline catalog`() = runTest {
        // Иначе офлайн-главная показывала бы то, что человек искал вчера,
        // вместо всего, что рядом.
        dao.seed(listOf(entity("old")))
        server.enqueue(json(PAGE_BODY))

        repository().places(DiscoveryFilters(query = "osh"))

        assertEquals(listOf("old"), dao.current().map { it.id })
    }

    @Test
    fun `caching drops entries older than the ttl`() = runTest {
        server.enqueue(json(PAGE_BODY))

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
        assertEquals(1, details.hours.size)
        assertEquals(listOf("r-1"), details.reviews.map { it.id })
        assertFalse(details.fromCache)
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

    private fun repository(): DefaultCatalogRepository {
        val api = NetworkFactory
            .retrofit(
                server.url("/").toString(),
                NetworkFactory.clientBuilder().build(),
                NetworkFactory.converterFactory(NetworkFactory.json()),
            )
            .create(CatalogApi::class.java)
        return DefaultCatalogRepository(api, dao, clock)
    }

    private fun entity(
        id: String,
        name: String = "Place $id",
        category: String = "food",
        distanceMeters: Int = 500,
        phone: String? = null,
    ) = PlaceDto(
        id = id,
        name = name,
        category = category,
        rating = 4.5,
        distanceMeters = distanceMeters,
        isOpenNow = true,
        reviewCount = 10,
        phone = phone,
    ).toEntity(NOW)

    private fun json(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody(body)

    private companion object {
        const val NOW = 1_774_000_000L
        const val CACHE_TTL_SECONDS = 7L * 24 * 60 * 60

        const val MIXED_PAGE_BODY = """
            {"items":[
              {"id":"food","name":"Food","category":"food","rating":4.1,"distanceMeters":300,"isOpenNow":true},
              {"id":"pharmacy","name":"Pharmacy","category":"pharmacy","rating":4.2,"distanceMeters":100,"isOpenNow":true},
              {"id":"unknown","name":"Barber","category":"barbershop","rating":4.3,"distanceMeters":200,"isOpenNow":true}
            ],"page":0,"totalPages":1,"totalElements":3}
        """

        const val PAGE_BODY = """
            {"items":[
              {"id":"far","name":"Far","category":"food","rating":4.1,"distanceMeters":900,"isOpenNow":true},
              {"id":"near","name":"Near","category":"food","rating":4.9,"distanceMeters":100,"isOpenNow":true}
            ],"page":0,"totalPages":3,"totalElements":42}
        """

        const val DETAILS_BODY = """
            {"id":"p-1","name":"Osh markazi","category":"food","rating":4.6,
             "distanceMeters":320,"isOpenNow":true,"reviewCount":42,
             "description":"Eng mazali osh","phone":"+998901234567",
             "photos":["a.jpg"],"hasQueue":true,
             "openingHours":[{"dayOfWeek":1,"opensAt":"09:00","closesAt":"18:00"}]}
        """

        const val REVIEWS_BODY = """
            {"items":[{"id":"r-1","author":"Ali","rating":5,"text":"Zo'r",
             "createdAt":"2026-08-25T10:15:30Z"}],"page":0,"totalPages":1}
        """
    }
}
