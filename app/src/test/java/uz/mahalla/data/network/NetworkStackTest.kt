package uz.mahalla.data.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
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
import uz.mahalla.data.location.DeviceLocation
import uz.mahalla.data.location.RequestLocationProvider
import uz.mahalla.data.network.auth.AuthApi
import uz.mahalla.data.prefs.Session
import uz.mahalla.feature.discovery.data.CatalogApi
import uz.mahalla.feature.discovery.data.PlaceDetailDto
import uz.mahalla.testutil.FakeDeviceInfoProvider
import uz.mahalla.testutil.FakeRequestLocationProvider
import uz.mahalla.testutil.FakeSessionStore
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
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
    private lateinit var sessionExpiry: SessionExpiry

    /**
     * События «сессия кончилась» (issue #138). Собираются в список, а не
     * проверяются флагом: лишнее событие — это лишний выброс на экран входа.
     */
    private lateinit var expiryEvents: MutableList<Unit>
    private lateinit var expiryScope: CoroutineScope

    private var locationProvider: RequestLocationProvider = FakeRequestLocationProvider()

    /** Фиксированные часы: срок жизни токена должен быть детерминированным. */
    private val fixedClock: Clock =
        Clock.fixed(Instant.ofEpochSecond(FIXED_NOW_EPOCH_SECONDS), ZoneOffset.UTC)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        sessionStore = FakeSessionStore()
        sessionExpiry = SessionExpiry()
        expiryEvents = CopyOnWriteArrayList()
        // `Dispatchers.Unconfined`: подписка регистрируется до выхода из
        // `launch`, а событие приезжает на том же потоке, где его отправили, —
        // authenticator работает на пуле OkHttp, и ждать чужой поток тест не
        // должен. `SessionExpiry` без replay: подписаться надо заранее.
        expiryScope = CoroutineScope(Dispatchers.Unconfined)
        expiryScope.launch { sessionExpiry.expired.collect { expiryEvents += it } }
    }

    @After
    fun tearDown() {
        expiryScope.cancel()
        server.shutdown()
    }

    @Test
    fun `parses a successful response`() = runTest {
        server.enqueue(jsonResponse(PLACE_BODY))

        val result = apiCall { catalogApi().place("p-1").payload() }

        assertEquals(
            ApiResult.Success(
                PlaceDetailDto(
                    id = "p-1",
                    name = "Osh markazi",
                    category = "FOOD",
                    ratingAvg = 4.6,
                    ratingCount = 42,
                    isAvailable = true,
                ),
            ),
            result,
        )
    }

    @Test
    fun `unknown fields do not break parsing`() = runTest {
        server.enqueue(
            jsonResponse(
                """{"success":true,"data":{"id":"p-1","name":"Osh markazi","loyaltyTier":"gold"}}""",
            ),
        )

        val result = apiCall { catalogApi().place("p-1").payload() }

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
        assertEquals("сессия жива — на вход выгонять некого", 0, expiryEvents.size)
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
        // Стереть токены недостаточно: человек остался бы внутри приложения,
        // где каждый экран отвечает 401 (issue #138).
        assertEquals(1, expiryEvents.size)
    }

    @Test
    fun `a broken connection during refresh keeps the session`() = runTest {
        // Обрыв связи — это «спросить не удалось», а не «сессия кончилась».
        // Стереть токены значило бы выкинуть на экран входа человека, у
        // которого они живы, из-за секундной потери сети (issue #138).
        sessionStore.save(Session("stale", "refresh-1"))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val result = apiCall { catalogApi().place("p-1") }

        // И экран узнаёт правду: «нет сети», а не 401 с текстом «Kirish uchun
        // autentifikatsiya talab qilinadi» при живой сессии (issue #239).
        assertEquals(ApiError.NoConnection, (result as ApiResult.Failure).error)
        assertEquals(Session("stale", "refresh-1"), sessionStore.current())
        assertEquals("на вход выгонять некого", 0, expiryEvents.size)
    }

    @Test
    fun `a stalled refresh keeps the session and reports a timeout`() = runTest {
        sessionStore.save(Session("stale", "refresh-1"))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(jsonResponse(REFRESHED_TOKENS_BODY).setBodyDelay(2, TimeUnit.SECONDS))

        val result = apiCall { catalogApi(readTimeoutMillis = 250).place("p-1") }

        assertEquals(ApiError.Timeout, (result as ApiResult.Failure).error)
        assertEquals(Session("stale", "refresh-1"), sessionStore.current())
        assertEquals(0, expiryEvents.size)
    }

    @Test
    fun `a server error during refresh keeps the session`() = runTest {
        // Авария бэкенда лечится сама; разлогин всех пользователей — нет.
        sessionStore.save(Session("stale", "refresh-1"))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(503))

        apiCall { catalogApi().place("p-1") }

        assertEquals(Session("stale", "refresh-1"), sessionStore.current())
        assertEquals(0, expiryEvents.size)
    }

    @Test
    fun `a throttled refresh keeps the session`() = runTest {
        // 429 говорит «зайдите позже», а не «токен мёртв». Выход на экран
        // входа стоит человеку платного SMS и регистрации заново — за
        // троттлинг такой цены быть не должно.
        sessionStore.save(Session("stale", "refresh-1"))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(429))

        apiCall { catalogApi().place("p-1") }

        assertEquals(Session("stale", "refresh-1"), sessionStore.current())
        assertEquals(0, expiryEvents.size)
    }

    @Test
    fun `a refresh token rejected by the backend ends the session`() = runTest {
        // Так бэкенд отвечает на мёртвую сессию (`BankAuthService.refreshToken`):
        // 401 с кодом `TOKEN_EXPIRED` / `TOKEN_INVALID` / `TOKEN_HIJACK`.
        sessionStore.save(Session("stale", "refresh-1"))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
            envelopeError(
                httpCode = 401,
                code = "TOKEN_HIJACK",
                message = "Xavfsizlik muammosi aniqlandi. Barcha sessiyalar bekor qilindi.",
            ),
        )

        val result = apiCall { catalogApi().place("p-1") }

        assertEquals(ApiError.Unauthorized, (result as ApiResult.Failure).error)
        assertNull(sessionStore.current())
        assertEquals(1, expiryEvents.size)
    }

    @Test
    fun `a refresh refused by the geo filter keeps the session`() = runTest {
        // 403 `GEO_*` на refresh — это `geoService.requireLocation`, первая
        // строка `refreshToken`, до разбора токена: токен тут ни при чём, и
        // вход заново координат не добавит.
        sessionStore.save(Session("stale", "refresh-1"))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
            envelopeError(
                httpCode = 403,
                code = "GEO_PERMISSION_REQUIRED",
                message = "Joylashuv ruxsatini yoqing",
            ),
        )

        apiCall { catalogApi().place("p-1") }

        assertEquals(Session("stale", "refresh-1"), sessionStore.current())
        assertEquals(0, expiryEvents.size)
    }

    @Test
    fun `a refresh refused for the shape of the request keeps the session`() = runTest {
        // 400 — «не понял запрос», а не «токен мёртв»: новое обязательное поле
        // на бэкенде иначе разлогинило бы всех пользователей разом.
        sessionStore.save(Session("stale", "refresh-1"))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
            envelopeError(httpCode = 400, code = "VALIDATION_ERROR", message = "lat: required"),
        )

        apiCall { catalogApi().place("p-1") }

        assertEquals(Session("stale", "refresh-1"), sessionStore.current())
        assertEquals(0, expiryEvents.size)
    }

    @Test
    fun `a refresh answered without tokens keeps the session`() = runTest {
        // Бэкенд на refresh так не отвечает: удачный ответ у него всегда с
        // парой токенов. Значит это подмена ответа или сломанный контракт, а
        // не отказ — за чужой прокси человек платить SMS не должен.
        sessionStore.save(Session("stale", "refresh-1"))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(jsonResponse("""{"success":true,"data":{"sessionId":"s-1"}}"""))

        apiCall { catalogApi().place("p-1") }

        assertEquals(Session("stale", "refresh-1"), sessionStore.current())
        assertEquals(0, expiryEvents.size)
    }

    @Test
    fun `several unparseable refresh responses end the session`() = runTest {
        // Один такой ответ прощаем — мало ли что с сетью (см. тест выше).
        // Но без явного выхода приложение иначе виснет в «везде 401»
        // навсегда: разбитый контракт сам себя не чинит (issue #198).
        sessionStore.save(Session("stale", "refresh-1"))
        val api = catalogApi()

        repeat(2) {
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(jsonResponse("""{"success":true,"data":{"sessionId":"s-1"}}"""))
            apiCall { api.place("p-1") }
            assertEquals(Session("stale", "refresh-1"), sessionStore.current())
            assertEquals(0, expiryEvents.size)
        }

        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(jsonResponse("""{"success":true,"data":{"sessionId":"s-1"}}"""))
        apiCall { api.place("p-1") }

        assertNull("третий подряд — контракт сломан, а не Wi-Fi", sessionStore.current())
        assertEquals(1, expiryEvents.size)
    }

    @Test
    fun `a refusal inside a 2xx envelope keeps the session`() = runTest {
        // `success: false` при 200: бэкенд отказы отдаёт с HTTP-кодом
        // (`GlobalExceptionHandler`), так что и это не его ответ.
        sessionStore.save(Session("stale", "refresh-1"))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
            envelopeError(httpCode = 200, code = "TOKEN_INVALID", message = "Token noto'g'ri"),
        )

        apiCall { catalogApi().place("p-1") }

        assertEquals(Session("stale", "refresh-1"), sessionStore.current())
        assertEquals(0, expiryEvents.size)
    }

    @Test
    fun `a failure to describe the device keeps the session and stays inside`() = runTest {
        // Координаты и устройство собираются перед refresh. Их сбой — это
        // «спросить не удалось»: исключение не должно выйти из authenticator'а.
        // Иначе OkHttp превращает его в «canceled» для запроса и пробрасывает
        // дальше в поток диспетчера — то есть роняет приложение.
        locationProvider = object : RequestLocationProvider {
            override suspend fun current(): DeviceLocation = error("DataStore недоступен")
        }
        sessionStore.save(Session("stale", "refresh-1"))
        server.enqueue(MockResponse().setResponseCode(401))

        val result = apiCall { catalogApi().place("p-1") }

        assertEquals(ApiError.Unauthorized, (result as ApiResult.Failure).error)
        assertEquals("refresh даже не ушёл", 1, server.requestCount)
        assertEquals(Session("stale", "refresh-1"), sessionStore.current())
        assertEquals(0, expiryEvents.size)
    }

    @Test
    fun `401 without a session is not retried`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = apiCall { catalogApi().place("p-1") }

        assertEquals(ApiError.Unauthorized, (result as ApiResult.Failure).error)
        assertEquals(1, server.requestCount)
        // Сессии не было и до запроса: это не «она кончилась», а анонимный
        // запрос к закрытой ручке. Разбираться с этим стартовому экрану.
        assertEquals(0, expiryEvents.size)
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
    fun `one endpoint repeatedly rejecting a fresh token keeps the session`() = runTest {
        // Ручка отвечает 401 вместо 403 (роль, подписка, бизнес-панель) — это
        // её личная проблема, а не повод разлогинить человека, у которого всё
        // остальное работает (issue #197).
        sessionStore.save(Session("stale", "refresh-1"))
        val request = staleRequest()
        val auth = authenticator()

        repeat(3) {
            val retry = auth.authenticate(
                route = null,
                response = unauthorized(request, prior = unauthorized(request)),
            )
            assertNull(retry)
        }

        assertEquals(Session("stale", "refresh-1"), sessionStore.current())
        assertEquals(0, expiryEvents.size)
    }

    @Test
    fun `two different endpoints rejecting a fresh token end the session`() = runTest {
        // Если это не одна ручка, а несколько разных, — токен отвергнут
        // системно, и это уже сама сессия (issue #197).
        sessionStore.save(Session("stale", "refresh-1"))
        val requestA = staleRequest()
        val requestB = Request.Builder()
            .url(server.url("/places/p-2"))
            .header(AuthInterceptor.HEADER_AUTHORIZATION, "Bearer stale")
            .build()
        val auth = authenticator()

        auth.authenticate(
            route = null,
            response = unauthorized(requestA, prior = unauthorized(requestA)),
        )
        assertEquals("одной ручки мало", Session("stale", "refresh-1"), sessionStore.current())
        assertEquals(0, expiryEvents.size)

        val retry = auth.authenticate(
            route = null,
            response = unauthorized(requestB, prior = unauthorized(requestB)),
        )

        assertNull(retry)
        assertNull(sessionStore.current())
        assertEquals(1, expiryEvents.size)
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
            sessionExpiry = sessionExpiry,
            authApi = authApi,
            deviceInfoProvider = FakeDeviceInfoProvider(),
            locationProvider = locationProvider,
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

    /** Отказ в конверте бэкенда (`GlobalExceptionHandler`). */
    private fun envelopeError(httpCode: Int, code: String, message: String): MockResponse =
        jsonResponse("""{"success":false,"error":{"code":"$code","message":"$message"}}""")
            .setResponseCode(httpCode)

    private companion object {
        const val DEFAULT_READ_TIMEOUT_MILLIS = 5_000L
        const val FIXED_NOW_EPOCH_SECONDS = 1_774_000_000L

        /** Ответ каталога тоже в конверте бэкенда (issue #53). */
        const val PLACE_BODY = """
            {"success":true,"data":{"id":"p-1","name":"Osh markazi","category":"FOOD",
             "ratingAvg":4.6,"ratingCount":42,"isAvailable":true}}
        """

        /** Конверт бэкенда: пара токенов лежит в `data.tokens` (issue #42). */
        const val REFRESHED_TOKENS_BODY = """
            {"success":true,"data":{"sessionId":"s-1",
             "tokens":{"accessToken":"fresh","refreshToken":"refresh-2","accessExpiresIn":3600}}}
        """
    }
}
