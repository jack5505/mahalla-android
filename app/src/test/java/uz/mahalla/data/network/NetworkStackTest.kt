package uz.mahalla.data.network

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
import uz.mahalla.core.result.apiCall
import uz.mahalla.data.network.auth.AuthApi
import uz.mahalla.data.prefs.Session
import uz.mahalla.feature.discovery.data.CatalogApi
import uz.mahalla.feature.discovery.data.PlaceDto
import uz.mahalla.testutil.FakeSessionStore
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/**
 * Сетевой стек целиком (эпик 1.3): успех, Bearer, 401 + refresh, провалившийся
 * refresh, таймаут и битый JSON.
 *
 * Клиент собирается тем же [NetworkFactory], что и в проде, — тест проверяет
 * production-конфигурацию, а не свою копию.
 */
class NetworkStackTest {

    private lateinit var server: MockWebServer
    private lateinit var sessionStore: FakeSessionStore

    /** Фиксированные часы: срок жизни токена должен быть детерминированным. */
    private val fixedClock: Clock =
        Clock.fixed(Instant.ofEpochSecond(FIXED_NOW_EPOCH_SECONDS), ZoneOffset.UTC)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        sessionStore = FakeSessionStore()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `parses a successful response`() = runTest {
        server.enqueue(jsonResponse(PLACE_BODY))

        val result = apiCall { catalogApi().place("p-1") }

        assertEquals(
            ApiResult.Success(
                PlaceDto(
                    id = "p-1",
                    name = "Osh markazi",
                    category = "food",
                    rating = 4.6,
                    distanceMeters = 320,
                    isOpenNow = true,
                ),
            ),
            result,
        )
    }

    @Test
    fun `unknown fields do not break parsing`() = runTest {
        server.enqueue(jsonResponse("""{"id":"p-1","name":"Osh markazi","loyaltyTier":"gold"}"""))

        val result = apiCall { catalogApi().place("p-1") }

        assertEquals("p-1", (result as ApiResult.Success).data.id)
    }

    @Test
    fun `anonymous request goes without an authorization header`() = runTest {
        server.enqueue(jsonResponse(PLACE_BODY))

        apiCall { catalogApi().place("p-1") }

        assertNull(server.takeRequest().getHeader(AuthInterceptor.HEADER_AUTHORIZATION))
    }

    @Test
    fun `access token is attached as a bearer`() = runTest {
        sessionStore.save(Session("access-1", "refresh-1"))
        server.enqueue(jsonResponse(PLACE_BODY))

        apiCall { catalogApi().place("p-1") }

        assertEquals(
            "Bearer access-1",
            server.takeRequest().getHeader(AuthInterceptor.HEADER_AUTHORIZATION),
        )
    }

    @Test
    fun `401 triggers refresh and replays the request`() = runTest {
        sessionStore.save(Session("stale", "refresh-1"))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(jsonResponse(REFRESHED_TOKENS_BODY))
        server.enqueue(jsonResponse(PLACE_BODY))

        val result = apiCall { catalogApi().place("p-1") }

        assertTrue(result is ApiResult.Success)
        assertEquals(3, server.requestCount)

        val original = server.takeRequest()
        val refresh = server.takeRequest()
        val replay = server.takeRequest()
        assertEquals("Bearer stale", original.getHeader(AuthInterceptor.HEADER_AUTHORIZATION))
        assertEquals("/auth/refresh", refresh.path)
        assertEquals("Bearer fresh", replay.getHeader(AuthInterceptor.HEADER_AUTHORIZATION))

        assertEquals(
            Session("fresh", "refresh-2", FIXED_NOW_EPOCH_SECONDS + 3600),
            sessionStore.current(),
        )
        assertEquals("сессия перезаписана один раз", 2, sessionStore.saveCount)
    }

    @Test
    fun `failed refresh clears the session and reports unauthorized`() = runTest {
        sessionStore.save(Session("stale", "refresh-1"))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))

        val result = apiCall { catalogApi().place("p-1") }

        assertEquals(ApiResult.Failure(ApiError.Unauthorized), result)
        assertNull(sessionStore.current())
        // Ровно один повтор: исходный запрос + refresh, без бесконечного цикла.
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `401 without a session is not retried`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = apiCall { catalogApi().place("p-1") }

        assertEquals(ApiResult.Failure(ApiError.Unauthorized), result)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `stalled response is reported as a timeout`() = runTest {
        server.enqueue(jsonResponse(PLACE_BODY).setBodyDelay(2, TimeUnit.SECONDS))

        val result = apiCall { catalogApi(readTimeoutMillis = 250).place("p-1") }

        assertEquals(ApiResult.Failure(ApiError.Timeout), result)
    }

    @Test
    fun `broken json is reported as a serialization error`() = runTest {
        server.enqueue(jsonResponse("""{"id": "p-1", "name": """))

        val result = apiCall { catalogApi().place("p-1") }

        assertEquals(ApiResult.Failure(ApiError.Serialization), result)
    }

    private fun catalogApi(readTimeoutMillis: Long = DEFAULT_READ_TIMEOUT_MILLIS): CatalogApi {
        val baseUrl = server.url("/").toString()
        val converterFactory = NetworkFactory.converterFactory(NetworkFactory.json())

        // Refresh ходит «голым» клиентом — иначе 401 на refresh снова позвал
        // бы authenticator.
        val refreshClient = NetworkFactory.clientBuilder()
            .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
            .build()
        val authApi = NetworkFactory.retrofit(baseUrl, refreshClient, converterFactory)
            .create(AuthApi::class.java)

        val client = NetworkFactory.clientBuilder()
            .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
            .addInterceptor(AuthInterceptor(sessionStore))
            .authenticator(TokenAuthenticator(sessionStore, authApi, fixedClock))
            .build()

        return NetworkFactory.retrofit(baseUrl, client, converterFactory)
            .create(CatalogApi::class.java)
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody(body)

    private companion object {
        const val DEFAULT_READ_TIMEOUT_MILLIS = 5_000L
        const val FIXED_NOW_EPOCH_SECONDS = 1_774_000_000L

        const val PLACE_BODY = """
            {"id":"p-1","name":"Osh markazi","category":"food",
             "rating":4.6,"distanceMeters":320,"isOpenNow":true}
        """

        const val REFRESHED_TOKENS_BODY = """
            {"accessToken":"fresh","refreshToken":"refresh-2","expiresIn":3600}
        """
    }
}
