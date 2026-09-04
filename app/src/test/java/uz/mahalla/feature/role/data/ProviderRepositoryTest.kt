package uz.mahalla.feature.role.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.data.location.DeviceLocation
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.onboarding.domain.City
import uz.mahalla.feature.map.domain.MapPoint
import uz.mahalla.feature.onboarding.domain.PhoneNumberValidator
import uz.mahalla.feature.role.domain.PlaceModerationStatus
import uz.mahalla.feature.role.domain.ProviderForm
import uz.mahalla.testutil.FakeLocationSource

/**
 * Заявка продавца (issue #84) на настоящем сетевом стеке ([NetworkFactory] +
 * [MockWebServer]): подмена Retrofit фейком не поймала бы ни ошибку в пути
 * запроса, ни несовпадение схемы JSON.
 *
 * Имена полей тела выведены из ответа `Detail`: в `/v3/api-docs` тело
 * `POST /places` объявлено как `CreateRequest`, а это имя перекрыто коллизией
 * springdoc (см. KDoc [ProviderApi]). Тест закрепляет то, что приложение
 * отправляет, — сверить с бэкендом придётся живым запросом под токеном.
 */
class ProviderRepositoryTest {

    private lateinit var server: MockWebServer
    private val locationSource = FakeLocationSource()

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
    fun `application is posted with every field the form collected`() = runTest {
        server.enqueue(
            envelope(
                """{"id":"p-1","name":"Osh Markazi","category":"FOOD","status":"PENDING"}""",
            ),
        )
        locationSource.location = DeviceLocation(latitude = 41.2995, longitude = 69.2401)

        val result = repository().registerPlace(
            validForm().copy(description = "  Osh va lagman  ", website = "mahalla.uz"),
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/places", request.path)
        val body = request.body.readUtf8()
        assertTrue(body, """"name":"Osh Markazi"""" in body)
        assertTrue(body, """"category":"FOOD"""" in body)
        assertTrue(body, """"address":"Chilonzor, 12-kvartal"""" in body)
        assertTrue(body, """"phone":"+998901234567"""" in body)
        assertTrue(body, """"city":"tashkent"""" in body)
        assertTrue(body, """"description":"Osh va lagman"""" in body)
        // Схема дописывается на клиенте: требовать её от владельца кафе —
        // верный способ получить пустое поле.
        assertTrue(body, """"website":"https://mahalla.uz"""" in body)
        // Координаты измеренные, а не по городу: устройство их знает.
        assertTrue(body, """"lat":41.2995""" in body)
        assertTrue(body, """"lng":69.2401""" in body)

        val place = (result as ApiResult.Success).data
        assertEquals("p-1", place.id)
        assertEquals("Osh Markazi", place.name)
        assertEquals(PlaceModerationStatus.Pending, place.status)
    }

    @Test
    fun `city centre is used when the device has no position`() = runTest {
        server.enqueue(envelope("""{"id":"p-2","status":"PENDING"}"""))
        locationSource.location = null

        repository().registerPlace(validForm().copy(city = City.SAMARKAND))

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, """"lat":${City.SAMARKAND.latitude}""" in body)
        assertTrue(body, """"lng":${City.SAMARKAND.longitude}""" in body)
    }

    @Test
    fun `the point chosen on the map beats the position of the device`() = runTest {
        server.enqueue(envelope("""{"id":"p-4","status":"PENDING"}"""))
        // Заявку часто заполняют дома, а заведение стоит в другом месте:
        // запомнить надо то, что человек показал сам (issue #90).
        locationSource.location = DeviceLocation(latitude = 41.2995, longitude = 69.2401)

        repository().registerPlace(
            validForm().copy(location = MapPoint(latitude = 41.326543, longitude = 69.228765)),
        )

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, """"lat":41.326543""" in body)
        assertTrue(body, """"lng":69.228765""" in body)
    }

    @Test
    fun `the device is not even asked when the point is chosen`() = runTest {
        server.enqueue(envelope("""{"id":"p-5","status":"PENDING"}"""))

        repository().registerPlace(
            validForm().copy(location = MapPoint(latitude = 41.326543, longitude = 69.228765)),
        )

        // Лишнее обращение к LocationManager за ответом, который всё равно
        // проиграет выбранной точке.
        assertEquals(0, locationSource.callCount)
    }

    @Test
    fun `empty optional fields are absent, not null`() = runTest {
        server.enqueue(envelope("""{"id":"p-3","status":"PENDING"}"""))

        repository().registerPlace(validForm())

        val body = server.takeRequest().body.readUtf8()
        assertFalse(body, "description" in body)
        assertFalse(body, "website" in body)
        assertFalse(body, "null" in body)
    }

    @Test
    fun `unfilled form never reaches the network`() = runTest {
        val result = repository().registerPlace(validForm().copy(name = ""))

        // 400 от сервера сказал бы то же самое, но платой были бы запрос и
        // молчание экрана на время его выполнения.
        assertEquals(0, server.requestCount)
        assertEquals(
            ApiError.Business(ProviderRepository.INVALID_FORM_CODE),
            (result as ApiResult.Failure).error,
        )
    }

    @Test
    fun `answer without a name falls back to what the person typed`() = runTest {
        server.enqueue(envelope("""{"status":"ACTIVE"}"""))

        val place = (repository().registerPlace(validForm()) as ApiResult.Success).data

        assertEquals("Osh Markazi", place.name)
        assertEquals("", place.id)
        assertEquals(PlaceModerationStatus.Active, place.status)
    }

    @Test
    fun `server refusal is shown with its own text`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"PLACE_EXISTS",
                       "message":"Bunday muassasa allaqachon ro'yxatdan o'tgan"}}""",
                ),
        )

        val failure = (repository().registerPlace(validForm()) as ApiResult.Failure).failure

        assertEquals("PLACE_EXISTS", failure.server?.code)
        assertEquals("Bunday muassasa allaqachon ro'yxatdan o'tgan", failure.serverMessage)
    }

    @Test
    fun `success false is a failure, not an accepted application`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody("""{"success":false,"error":{"code":"VALIDATION_ERROR"}}"""),
        )

        val result = repository().registerPlace(validForm())

        assertEquals(ApiError.Business("VALIDATION_ERROR"), (result as ApiResult.Failure).error)
    }

    @Test
    fun `application without a session is reported as unauthorized`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        assertEquals(
            ApiError.Unauthorized,
            (repository().registerPlace(validForm()) as ApiResult.Failure).error,
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
        phoneValidator = PhoneNumberValidator(),
    )

    private fun validForm(): ProviderForm = ProviderForm(
        name = "Osh Markazi",
        category = PlaceCategory.Food,
        city = City.TASHKENT,
        address = "Chilonzor, 12-kvartal",
        phoneDigits = "901234567",
    )

    private fun envelope(data: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody("""{"success":true,"data":$data}""")
}
