package uz.mahalla.feature.role.data

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
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.onboarding.domain.PhoneNumberValidator
import uz.mahalla.feature.role.domain.MyPlace
import uz.mahalla.feature.role.domain.PlaceModerationStatus
import uz.mahalla.feature.role.domain.PlaceStaffRole
import uz.mahalla.testutil.FakeLocationSource
import uz.mahalla.testutil.FakeRequestLocationProvider

/**
 * «Мои заведения» (issue #94) на настоящем сетевом стеке ([NetworkFactory] +
 * [MockWebServer]): подмена Retrofit фейком не поймала бы ни ошибку в пути
 * запроса, ни несовпадение схемы JSON.
 *
 * Контракт снят со стенда: `GET places/my?page&size` → конверт с
 * `PageResponseMine`, `PUT places/{id}/availability` с телом `{lat, lng}` →
 * конверт с `Boolean`. Схемы `Mine` и `ToggleAvailabilityRequest` в
 * `/v3/api-docs` встречаются по одному разу — коллизии springdoc, из-за
 * которой поля заявки в issue #84 приходилось выводить из ответа, здесь нет.
 */
class MyPlacesRepositoryTest {

    private lateinit var server: MockWebServer
    private val locationSource = FakeLocationSource()
    private val requestLocation = FakeRequestLocationProvider()

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
    fun `the list is requested by page and parsed out of the envelope`() = runTest {
        server.enqueue(
            envelope(
                """{"content":[{"id":"p-1","name":"Osh Markazi","category":"FOOD",
                   "address":"Chilonzor, 12-kvartal","lat":41.29,"lng":69.24,
                   "status":"PENDING","isAvailable":true,"ratingAvg":4.6,
                   "ratingCount":128,"role":"OWNER"}],"page":0,"last":true}""",
            ),
        )

        val page = (repository().myPlaces() as ApiResult.Success).data

        assertEquals("/places/my?page=0&size=20", server.takeRequest().path)
        val place = page.items.single()
        assertEquals("p-1", place.id)
        assertEquals("Osh Markazi", place.name)
        assertEquals(PlaceCategory.Food, place.category)
        assertEquals("Chilonzor, 12-kvartal", place.address)
        // Главное в этой задаче: заявка видна со своим статусом.
        assertEquals(PlaceModerationStatus.Pending, place.status)
        assertTrue(place.isAvailable)
        assertEquals(4.6, place.rating, 0.0001)
        assertEquals(128, place.ratingCount)
        assertEquals(PlaceStaffRole.Owner, place.staffRole)
        assertFalse(page.hasMore)
    }

    @Test
    fun `availability is accepted under both names the backend may use`() = runTest {
        server.enqueue(
            envelope(
                """{"content":[{"id":"p-1","available":true},{"id":"p-2","isAvailable":true},
                   {"id":"p-3"}],"last":true}""",
            ),
        )

        val page = (repository().myPlaces() as ApiResult.Success).data

        // Ошибка здесь показала бы закрытыми все заведения.
        assertEquals(listOf(true, true, false), page.items.map(MyPlace::isAvailable))
    }

    @Test
    fun `an entry without an id is dropped instead of breaking the list`() = runTest {
        server.enqueue(
            envelope("""{"content":[{"name":"?"},{"id":"p-2"}],"page":1,"totalPages":3}"""),
        )

        val page = (repository().myPlaces(page = 1) as ApiResult.Success).data

        assertEquals(listOf("p-2"), page.items.map(MyPlace::id))
        assertEquals("/places/my?page=1&size=20", server.takeRequest().path)
        // `last` не приехал — «есть ли ещё» считается по номеру страницы.
        assertTrue(page.hasMore)
    }

    @Test
    fun `an unknown status and category do not hide the place`() = runTest {
        server.enqueue(
            envelope(
                """{"content":[{"id":"p-1","name":" ","category":"MUSEUM",
                   "status":"ARCHIVED","role":"CASHIER"}],"last":true}""",
            ),
        )

        val place = (repository().myPlaces() as ApiResult.Success).data.items.single()

        // Пропасть из списка своих заведений оно не должно ни в одном случае:
        // имя подставит экран, статус показывается как есть.
        assertEquals("", place.name)
        assertEquals(PlaceCategory.Other, place.category)
        assertEquals(PlaceModerationStatus.Unknown, place.status)
        assertEquals(PlaceStaffRole.Unknown, place.staffRole)
        assertNull(place.address)
        assertFalse(place.isOpenable)
    }

    @Test
    fun `silence about paging stops the load more loop`() = runTest {
        server.enqueue(envelope("""{"content":[{"id":"p-1"}]}"""))

        // Иначе экран догружал бы одну и ту же страницу до бесконечности.
        assertFalse((repository().myPlaces() as ApiResult.Success).data.hasMore)
    }

    @Test
    fun `availability toggle sends the device coordinates the schema requires`() = runTest {
        server.enqueue(envelope("false"))
        requestLocation.location = DeviceLocation(latitude = 41.2995, longitude = 69.2401)

        val result = repository().toggleAvailability(placeId = "p-1", current = true)

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/places/p-1/availability", request.path)
        // Желаемого состояния в теле нет вовсе — ручка переключатель.
        assertEquals(
            """{"lat":41.2995,"lng":69.2401}""",
            request.body.readUtf8(),
        )
        assertFalse((result as ApiResult.Success).data)
    }

    @Test
    fun `a silent server is read as a flipped flag`() = runTest {
        // 2xx с `success:true` и без `data`: значение сервер не назвал, но
        // запрос принял — прежнее состояние показывать нельзя.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody("""{"success":true}"""),
        )

        val result = repository().toggleAvailability(placeId = "p-1", current = false)

        assertTrue((result as ApiResult.Success).data)
    }

    @Test
    fun `refused toggle is reported with the text of the backend`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"PLACE_FORBIDDEN",
                       "message":"Bu muassasa sizga tegishli emas"}}""",
                ),
        )

        val failure = (
            repository().toggleAvailability("p-1", current = true) as ApiResult.Failure
            ).failure

        assertEquals("PLACE_FORBIDDEN", failure.server?.code)
        assertEquals("Bu muassasa sizga tegishli emas", failure.serverMessage)
    }

    @Test
    fun `success false is a failure, not an empty list`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"PLACES_UNAVAILABLE",
                       "message":"Muassasalar vaqtincha ishlamayapti"}}""",
                ),
        )

        val failure = (repository().myPlaces() as ApiResult.Failure).failure

        assertEquals(ApiError.Business("PLACES_UNAVAILABLE"), failure.error)
        assertEquals("Muassasalar vaqtincha ishlamayapti", failure.serverMessage)
    }

    @Test
    fun `expired token is reported as unauthorized`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        assertEquals(
            ApiError.Unauthorized,
            (repository().myPlaces() as ApiResult.Failure).error,
        )
    }

    /**
     * Стенд отвечает на `places/my` без токена `500 INTERNAL_ERROR`, а не
     * `401` (дефект бэкенда, §6 `docs/BACKEND-SYNC.md`). Клиент обязан
     * показать это как отказ сервера с его же текстом — притворяться, что
     * знает причину, он не может.
     */
    @Test
    fun `the 500 the stand actually returns without a token stays a server failure`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"INTERNAL_ERROR",
                       "message":"Texnik muammo yuz berdi. Iltimos, keyinroq urinib ko'ring."}}""",
                ),
        )

        val failure = (repository().myPlaces() as ApiResult.Failure).failure

        assertEquals(500, (failure.error as ApiError.Http).code)
        assertEquals("INTERNAL_ERROR", failure.server?.code)
        assertEquals(
            "Texnik muammo yuz berdi. Iltimos, keyinroq urinib ko'ring.",
            failure.serverMessage,
        )
    }

    private fun repository() = DefaultProviderRepository(
        api = NetworkFactory
            .retrofit(
                server.url("/").toString(),
                NetworkFactory.clientBuilder().build(),
                NetworkFactory.converterFactory(NetworkFactory.json()),
            )
            .create(ProviderApi::class.java),
        locationSource = locationSource,
        requestLocation = requestLocation,
        phoneValidator = PhoneNumberValidator(),
    )

    private fun envelope(data: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody("""{"success":true,"data":$data}""")
}
