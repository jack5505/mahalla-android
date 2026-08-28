package uz.mahalla.data.network

import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Converter
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.data.network.auth.AuthApi
import uz.mahalla.data.prefs.Session
import uz.mahalla.feature.discovery.data.CatalogApi
import uz.mahalla.feature.discovery.data.PlaceDto
import uz.mahalla.testutil.FakeDeviceInfoProvider
import uz.mahalla.testutil.FakeRequestLocationProvider
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
            Session("fresh", "refresh-2", FIXED_NOW_EPOCH_SECONDS + 3600, sessionId = "s-1"),
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

        assertEquals(ApiError.Unauthorized, (result as ApiResult.Failure).error)
        assertNull(sessionStore.current())
        // Ровно один повтор: исходный запрос + refresh, без бесконечного цикла.
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `401 without a session is not retried`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = apiCall { catalogApi().place("p-1") }

        assertEquals(ApiError.Unauthorized, (result as ApiResult.Failure).error)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `stalled response is reported as a timeout`() = runTest {
        server.enqueue(jsonResponse(PLACE_BODY).setBodyDelay(2, TimeUnit.SECONDS))

        val result = apiCall { catalogApi(readTimeoutMillis = 250).place("p-1") }

        assertEquals(ApiResult.Failure(ApiError.Timeout), result)
    }

    @Test
    fun `refresh without expiresIn leaves the expiry unknown`() = runTest {
        sessionStore.save(Session("stale", "refresh-1"))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
            jsonResponse(
                """{"success":true,
                    "data":{"tokens":{"accessToken":"fresh","refreshToken":"refresh-2"}}}""",
            ),
        )
        server.enqueue(jsonResponse(PLACE_BODY))

        apiCall { catalogApi().place("p-1") }

        // Ноль означал бы «истёк в 1970», т.е. вечно просроченный токен.
        assertEquals(
            Session.UNKNOWN_EXPIRY,
            sessionStore.current()?.expiresAtEpochSeconds,
        )
    }

    @Test
    fun `a redirect in the chain does not consume the refresh attempt`() = runTest {
        sessionStore.save(Session("stale", "refresh-1"))
        server.enqueue(jsonResponse(REFRESHED_TOKENS_BODY))
        val request = staleRequest()

        val retry = authenticator().authenticate(
            route = null,
            response = unauthorized(request, prior = redirect(request)),
        )

        assertEquals(
            "Bearer fresh",
            retry?.header(AuthInterceptor.HEADER_AUTHORIZATION),
        )
    }

    @Test
    fun `a second 401 in the chain stops the refresh loop`() = runTest {
        sessionStore.save(Session("stale", "refresh-1"))
        val request = staleRequest()

        val retry = authenticator().authenticate(
            route = null,
            response = unauthorized(request, prior = unauthorized(request)),
        )

        assertNull(retry)
        assertEquals("refresh даже не запрашивался", 0, server.requestCount)
    }

    @Test
    fun `broken json is reported as a serialization error`() = runTest {
        server.enqueue(jsonResponse("""{"id": "p-1", "name": """))

        val result = apiCall { catalogApi().place("p-1") }

        assertEquals(ApiResult.Failure(ApiError.Serialization), result)
    }

    @Test
    fun `the error body of the server travels with the failure`() = runTest {
        // Сквозная проверка issue #34: тело ошибки не должно оставаться только
        // в инспекторе трафика.
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("content-type", "application/json")
                .setBody(
                    """{"success":false,"error":{"code":"GEO_PERMISSION_REQUIRED",""" +
                        """"message":"Joylashuv ruxsatini yoqing"}}""",
                ),
        )

        val result = apiCall { catalogApi().place("p-1") } as ApiResult.Failure

        assertEquals(ApiError.Forbidden, result.error)
        val payload = result.failure.server
        assertEquals("Joylashuv ruxsatini yoqing", payload?.message)
        assertEquals("GEO_PERMISSION_REQUIRED", payload?.code)
        assertEquals(403, payload?.httpCode)
        assertTrue(
            "адрес нужен, чтобы понять, куда именно ушёл запрос",
            payload?.requestLine?.startsWith("GET http") == true,
        )
        assertTrue(payload?.body?.contains("GEO_PERMISSION_REQUIRED") == true)
    }

    private fun catalogApi(readTimeoutMillis: Long = DEFAULT_READ_TIMEOUT_MILLIS): CatalogApi {
        val client = NetworkFactory.clientBuilder()
            .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
            .addInterceptor(AuthInterceptor(sessionStore))
            .authenticator(authenticator(readTimeoutMillis))
            .build()

        return NetworkFactory.retrofit(server.url("/").toString(), client, converterFactory())
            .create(CatalogApi::class.java)
    }

    private fun authenticator(
        readTimeoutMillis: Long = DEFAULT_READ_TIMEOUT_MILLIS,
    ): TokenAuthenticator {
        // Refresh ходит «голым» клиентом — иначе 401 на refresh снова позвал
        // бы authenticator.
        val refreshClient = NetworkFactory.clientBuilder()
            .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
            .build()
        val authApi = NetworkFactory
            .retrofit(server.url("/").toString(), refreshClient, converterFactory())
            .create(AuthApi::class.java)
        return TokenAuthenticator(
            sessionStore = sessionStore,
            authApi = authApi,
            deviceInfoProvider = FakeDeviceInfoProvider(),
            locationProvider = FakeRequestLocationProvider(),
            clock = fixedClock,
        )
    }

    private fun converterFactory(): Converter.Factory =
        NetworkFactory.converterFactory(NetworkFactory.json())

    /** Ответ собирается вручную: цепочку `priorResponse` иначе не задать. */
    private fun unauthorized(request: Request, prior: Response? = null): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .priorResponse(prior)
            .build()

    private fun redirect(request: Request): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(302)
        .message("Found")
        .build()

    private fun staleRequest(): Request = Request.Builder()
        .url(server.url("/places/p-1"))
        .header(AuthInterceptor.HEADER_AUTHORIZATION, "Bearer stale")
        .build()

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

        /** Конверт бэкенда: пара токенов лежит в `data.tokens` (issue #42). */
        const val REFRESHED_TOKENS_BODY = """
            {"success":true,"data":{"sessionId":"s-1",
             "tokens":{"accessToken":"fresh","refreshToken":"refresh-2","accessExpiresIn":3600}}}
        """
    }
}
