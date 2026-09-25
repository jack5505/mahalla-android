package uz.mahalla.data.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
import uz.mahalla.data.db.entity.CartDraftItemEntity
import uz.mahalla.data.db.entity.OrderEntity
import uz.mahalla.data.location.DeviceLocation
import uz.mahalla.data.location.RequestLocationProvider
import uz.mahalla.data.network.auth.AuthApi
import uz.mahalla.data.prefs.Session
import uz.mahalla.data.push.PushTokenRegistrar
import uz.mahalla.data.push.PushTokenStore
import uz.mahalla.data.session.LocalUserDataCleaner
import uz.mahalla.feature.discovery.data.CatalogApi
import uz.mahalla.feature.discovery.data.PlaceDetailDto
import uz.mahalla.testutil.FakeCartDraftDao
import uz.mahalla.testutil.FakeDeviceInfoProvider
import uz.mahalla.testutil.FakeOrderDao
import uz.mahalla.testutil.FakePreferencesDataStore
import uz.mahalla.testutil.FakePushTokenProvider
import uz.mahalla.testutil.FakeRequestLocationProvider
import uz.mahalla.testutil.FakeSessionStore
import java.time.Clock
import java.time.Duration
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

    private lateinit var orderDao: FakeOrderDao
    private lateinit var cartDraftDao: FakeCartDraftDao
    private lateinit var pushTokenStore: PushTokenStore
    private lateinit var pushTokenProvider: FakePushTokenProvider

    /**
     * Срок жизни токена должен быть детерминированным, но счётчик
     * неоднозначных провалов refresh (issue #301) требует ещё и умения
     * двигать время внутри теста — поэтому часы можно подвинуть явно, а не
     * только зафиксировать.
     */
    private val movableClock: MovableClock =
        MovableClock(Instant.ofEpochSecond(FIXED_NOW_EPOCH_SECONDS))

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
        orderDao = FakeOrderDao()
        cartDraftDao = FakeCartDraftDao()
        pushTokenStore = PushTokenStore(FakePreferencesDataStore())
        pushTokenProvider = FakePushTokenProvider()
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
    fun `a rejected refresh also wipes the order cache, the cart draft and the push token`() =
        runTest {
            // Стереть только токены недостаточно (issue #341): следующий
            // человек на этом же устройстве увидел бы чужие заказы и корзину
            // из офлайн-кэша и получал бы чужие пуши, пока сервер не
            // перепривяжет токен.
            sessionStore.save(Session("stale", "refresh-1"))
            orderDao.upsert(listOf(order(id = "o-1")))
            cartDraftDao.upsert(draft(placeId = "place-1", productId = "osh"))
            pushTokenStore.save("fcm-1")
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(401))

            apiCall { catalogApi().place("p-1") }

            assertEquals(emptyList<OrderEntity>(), orderDao.observeAll().first())
            assertEquals(emptyList<CartDraftItemEntity>(), cartDraftDao.observe("place-1").first())
            assertNull(pushTokenStore.current())
            assertEquals(1, pushTokenProvider.deleteCalls)
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
    fun `a new session after an explicit rejection starts the ambiguous counter over`() = runTest {
        // Тот же риск, что и у смерти по счётчику: если до явного отказа
        // сервера (401) уже был один прощённый неоднозначный ответ, счётчик
        // остаётся ненулевым, а следующий вход пишет сессию мимо
        // `TokenAuthenticator` — без сброса и здесь один неоднозначный ответ
        // в новой сессии сразу добил бы счёт (issue #198).
        sessionStore.save(Session("stale", "refresh-1"))
        val auth = authenticator()
        val staleRequest = staleRequest()

        server.enqueue(jsonResponse("""{"success":true,"data":{"""))
        auth.authenticate(route = null, response = unauthorized(staleRequest))
        assertEquals("прощённый неоднозначный ответ", Session("stale", "refresh-1"), sessionStore.current())

        server.enqueue(
            envelopeError(
                httpCode = 401,
                code = "TOKEN_HIJACK",
                message = "Xavfsizlik muammosi aniqlandi. Barcha sessiyalar bekor qilindi.",
            ),
        )
        auth.authenticate(route = null, response = unauthorized(staleRequest))
        assertNull("явный отказ убивает сессию", sessionStore.current())
        assertEquals(1, expiryEvents.size)

        sessionStore.save(Session("new-access", "new-refresh"))
        val newRequest = Request.Builder()
            .url(server.url("/places/p-1"))
            .header(AuthInterceptor.HEADER_AUTHORIZATION, "Bearer new-access")
            .build()

        server.enqueue(jsonResponse("""{"success":true,"data":{"sessionId":"s-2"}}"""))
        auth.authenticate(route = null, response = unauthorized(newRequest))

        assertEquals(
            "один неоднозначный ответ в новой сессии — снова прощаем",
            Session("new-access", "new-refresh"),
            sessionStore.current(),
        )
        assertEquals("второго выброса на вход не случилось", 1, expiryEvents.size)
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
    fun `three consecutive ambiguous refresh failures end the session`() = runTest {
        // Три подряд ответа, у которых нет причины, названной сервером — по
        // очереди неразобранное тело, `success: false` при 2xx и 2xx без
        // токенов, — то есть счётчик общий для всех трёх (issue #198), а не
        // отдельный на каждый тип. Разнесены по времени (issue #301) — иначе
        // это уже другой сценарий, см. `a burst of near-simultaneous ambiguous
        // refresh failures does not end the session`.
        sessionStore.save(Session("stale", "refresh-1"))
        val auth = authenticator()
        val request = staleRequest()

        server.enqueue(jsonResponse("""{"success":true,"data":{"""))
        assertNull(auth.authenticate(route = null, response = unauthorized(request)))
        assertEquals("первый — прощаем", Session("stale", "refresh-1"), sessionStore.current())
        assertEquals(0, expiryEvents.size)

        movableClock.advanceBy(TokenAuthenticator.MIN_AMBIGUOUS_REFRESH_GAP)
        server.enqueue(
            envelopeError(httpCode = 200, code = "TOKEN_INVALID", message = "Token noto'g'ri"),
        )
        assertNull(auth.authenticate(route = null, response = unauthorized(request)))
        assertEquals("второй — тоже", Session("stale", "refresh-1"), sessionStore.current())
        assertEquals(0, expiryEvents.size)

        movableClock.advanceBy(TokenAuthenticator.MIN_AMBIGUOUS_REFRESH_GAP)
        server.enqueue(jsonResponse("""{"success":true,"data":{"sessionId":"s-1"}}"""))
        assertNull(auth.authenticate(route = null, response = unauthorized(request)))
        assertNull("третий подряд — контракт сломан, а не прокси", sessionStore.current())
        assertEquals(1, expiryEvents.size)
    }

    @Test
    fun `three consecutive ambiguous refresh failures also wipe local user data`() = runTest {
        // Тот же второй путь смерти сессии (issue #198), тот же долг
        // (issue #341): контракт сломан, а не сервер явно отказал, но кэш
        // заказов и токен пушей всё равно принадлежат умершей сессии.
        sessionStore.save(Session("stale", "refresh-1"))
        orderDao.upsert(listOf(order(id = "o-1")))
        pushTokenStore.save("fcm-1")
        val auth = authenticator()
        val request = staleRequest()

        server.enqueue(jsonResponse("""{"success":true,"data":{"""))
        auth.authenticate(route = null, response = unauthorized(request))
        movableClock.advanceBy(TokenAuthenticator.MIN_AMBIGUOUS_REFRESH_GAP)
        server.enqueue(
            envelopeError(httpCode = 200, code = "TOKEN_INVALID", message = "Token noto'g'ri"),
        )
        auth.authenticate(route = null, response = unauthorized(request))
        movableClock.advanceBy(TokenAuthenticator.MIN_AMBIGUOUS_REFRESH_GAP)
        server.enqueue(jsonResponse("""{"success":true,"data":{"sessionId":"s-1"}}"""))
        auth.authenticate(route = null, response = unauthorized(request))

        assertNull(sessionStore.current())
        assertEquals(emptyList<OrderEntity>(), orderDao.observeAll().first())
        assertNull(pushTokenStore.current())
    }

    @Test
    fun `a burst of near-simultaneous ambiguous refresh failures does not end the session`() =
        runTest {
            // Несколько экранов упёрлись в 401 почти одновременно (например,
            // на холодном старте) — каждый берёт лок по очереди и делает свой
            // refresh, время между ответами не двигается. Чужой прокси
            // (issue #138) в этот момент может отдать одно и то же тело на
            // все эти близкие по времени запросы — это один инцидент, а не
            // MAX_AMBIGUOUS_REFRESH_FAILURES разных провалов контракта
            // подряд, и сессия не должна кончаться (issue #301).
            sessionStore.save(Session("stale", "refresh-1"))
            val auth = authenticator()
            val request = staleRequest()

            repeat(TokenAuthenticator.MAX_AMBIGUOUS_REFRESH_FAILURES + 2) {
                server.enqueue(jsonResponse("""{"success":true,"data":{"sessionId":"s-1"}}"""))
                assertNull(auth.authenticate(route = null, response = unauthorized(request)))
            }

            assertEquals(Session("stale", "refresh-1"), sessionStore.current())
            assertEquals(0, expiryEvents.size)
        }

    @Test
    fun `a burst counts once and the next genuinely later failures still add up`() = runTest {
        // Всплеск (без сдвига часов) не должен исчезать без следа: он
        // засчитан как один провал, а не как ноль, — и как только реальный
        // интервал снова проходит, следующие ответы продолжают считать с
        // этого одного, а не начинают заново.
        sessionStore.save(Session("stale", "refresh-1"))
        val auth = authenticator()
        val request = staleRequest()

        // Всплеск из двух почти одновременных ответов — засчитан один раз.
        server.enqueue(jsonResponse("""{"success":true,"data":{"sessionId":"s-1"}}"""))
        assertNull(auth.authenticate(route = null, response = unauthorized(request)))
        server.enqueue(jsonResponse("""{"success":true,"data":{"sessionId":"s-1"}}"""))
        assertNull(auth.authenticate(route = null, response = unauthorized(request)))
        assertEquals(0, expiryEvents.size)

        // Второй засчитанный — сессия ещё жива.
        movableClock.advanceBy(TokenAuthenticator.MIN_AMBIGUOUS_REFRESH_GAP)
        server.enqueue(jsonResponse("""{"success":true,"data":{"sessionId":"s-1"}}"""))
        assertNull(auth.authenticate(route = null, response = unauthorized(request)))
        assertEquals("второй засчитанный — сессия ещё жива", Session("stale", "refresh-1"), sessionStore.current())
        assertEquals(0, expiryEvents.size)

        // Третий засчитанный (после всплеска в счёте — только два) — конец.
        movableClock.advanceBy(TokenAuthenticator.MIN_AMBIGUOUS_REFRESH_GAP)
        server.enqueue(jsonResponse("""{"success":true,"data":{"sessionId":"s-1"}}"""))
        assertNull(auth.authenticate(route = null, response = unauthorized(request)))
        assertNull("третий засчитанный подряд — сессия кончена", sessionStore.current())
        assertEquals(1, expiryEvents.size)
    }

    @Test
    fun `a new session after death starts the ambiguous counter over`() = runTest {
        // Смерть сессии от контракта — не единственный путь сюда: следующий
        // человек логинится заново, и `SessionStore.save` пишет новую сессию
        // мимо `TokenAuthenticator`. Если счётчик не обнулить вместе с
        // `clear()`, первый же неоднозначный ответ в новой сессии добивает
        // счёт до порога и стирает её мгновенно — тот самый цикл «вход →
        // платный SMS → моментальный выход», от которого защищает #138.
        sessionStore.save(Session("stale", "refresh-1"))
        val auth = authenticator()
        val staleRequest = staleRequest()

        server.enqueue(jsonResponse("""{"success":true,"data":{"""))
        auth.authenticate(route = null, response = unauthorized(staleRequest))
        movableClock.advanceBy(TokenAuthenticator.MIN_AMBIGUOUS_REFRESH_GAP)
        server.enqueue(
            envelopeError(httpCode = 200, code = "TOKEN_INVALID", message = "Token noto'g'ri"),
        )
        auth.authenticate(route = null, response = unauthorized(staleRequest))
        movableClock.advanceBy(TokenAuthenticator.MIN_AMBIGUOUS_REFRESH_GAP)
        server.enqueue(jsonResponse("""{"success":true,"data":{"sessionId":"s-1"}}"""))
        auth.authenticate(route = null, response = unauthorized(staleRequest))
        assertNull("сессия погибла на третьем подряд", sessionStore.current())
        assertEquals(1, expiryEvents.size)

        // Новый вход — с тем же authenticator'ом, как это и будет в проде
        // (он `@Singleton`).
        sessionStore.save(Session("new-access", "new-refresh"))
        val newRequest = Request.Builder()
            .url(server.url("/places/p-1"))
            .header(AuthInterceptor.HEADER_AUTHORIZATION, "Bearer new-access")
            .build()

        server.enqueue(jsonResponse("""{"success":true,"data":{"sessionId":"s-2"}}"""))
        auth.authenticate(route = null, response = unauthorized(newRequest))

        assertEquals(
            "один неоднозначный ответ в новой сессии — снова прощаем",
            Session("new-access", "new-refresh"),
            sessionStore.current(),
        )
        assertEquals("второго выброса на вход не случилось", 1, expiryEvents.size)
    }

    @Test
    fun `a successful refresh resets the ambiguous refresh counter`() = runTest {
        // Прокси не отвечает одно и то же на каждый повторный refresh: если
        // между сбоями случился нормальный ответ, следующие сбои снова
        // начинают счёт с нуля, а не продолжают старый (issue #198).
        sessionStore.save(Session("stale", "refresh-1"))
        val auth = authenticator()
        val request = staleRequest()

        // Разнесены по времени (issue #301) — иначе оба ответа одной пары
        // схлопнутся во всплеск сами, и тест останется зелёным даже без
        // сброса счётчика удачным refresh.
        server.enqueue(jsonResponse("""{"success":true,"data":{"sessionId":"s-1"}}"""))
        auth.authenticate(route = null, response = unauthorized(request))
        movableClock.advanceBy(TokenAuthenticator.MIN_AMBIGUOUS_REFRESH_GAP)
        server.enqueue(jsonResponse("""{"success":true,"data":{"sessionId":"s-1"}}"""))
        auth.authenticate(route = null, response = unauthorized(request))

        movableClock.advanceBy(TokenAuthenticator.MIN_AMBIGUOUS_REFRESH_GAP)
        server.enqueue(jsonResponse(REFRESHED_TOKENS_BODY))
        auth.authenticate(route = null, response = unauthorized(request))
        // Токен снова протух: только что обновлённая сессия опять становится
        // той, что несёт `request` (`Bearer stale`), — иначе следующий 401 с
        // тем же запросом authenticator счёл бы уже обновлённым параллельно.
        sessionStore.save(Session("stale", "refresh-2"))

        server.enqueue(jsonResponse("""{"success":true,"data":{"sessionId":"s-1"}}"""))
        auth.authenticate(route = null, response = unauthorized(request))
        movableClock.advanceBy(TokenAuthenticator.MIN_AMBIGUOUS_REFRESH_GAP)
        server.enqueue(jsonResponse("""{"success":true,"data":{"sessionId":"s-1"}}"""))
        auth.authenticate(route = null, response = unauthorized(request))

        assertEquals(
            "два подряд после сброса — этого мало",
            Session("stale", "refresh-2"),
            sessionStore.current(),
        )
        assertEquals(0, expiryEvents.size)
    }

    @Test
    fun `repeated geo refusals never end the session`() = runTest {
        // 403 `GEO_*` — осмысленный отказ, не контрактная неоднозначность: в
        // общий счётчик issue #198 не идёт, сколько бы раз ни повторился.
        sessionStore.save(Session("stale", "refresh-1"))
        val auth = authenticator()
        val request = staleRequest()

        repeat(TokenAuthenticator.MAX_AMBIGUOUS_REFRESH_FAILURES + 2) {
            server.enqueue(
                envelopeError(
                    httpCode = 403,
                    code = "GEO_PERMISSION_REQUIRED",
                    message = "Joylashuv ruxsatini yoqing",
                ),
            )
            assertNull(auth.authenticate(route = null, response = unauthorized(request)))
        }

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
            clock = movableClock,
            localUserDataCleaner = LocalUserDataCleaner(
                orderDao = orderDao,
                cartDraftDao = cartDraftDao,
                pushTokenRegistrar = PushTokenRegistrar(pushTokenProvider, pushTokenStore),
            ),
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

    private fun order(id: String) = OrderEntity(
        id = id,
        placeId = "place-1",
        placeName = "Osh markazi",
        status = "NEW",
        totalSum = 50_000,
        createdAtEpochSeconds = FIXED_NOW_EPOCH_SECONDS,
    )

    private fun draft(placeId: String, productId: String) = CartDraftItemEntity(
        placeId = placeId,
        lineId = productId,
        productId = productId,
        name = productId,
        priceSum = 30_000,
        quantity = 1,
    )

    /** Часы, которые можно подвинуть: не только «когда», но и «насколько давно». */
    private class MovableClock(private var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
        fun advanceBy(duration: Duration) {
            now = now.plus(duration)
        }
    }

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
