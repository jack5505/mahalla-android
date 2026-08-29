package uz.mahalla.feature.auth.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.data.location.DeviceLocation
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.data.network.auth.AuthApi
import uz.mahalla.data.prefs.Session
import uz.mahalla.feature.auth.domain.LoginResult
import uz.mahalla.feature.auth.domain.OtpChallenge
import uz.mahalla.feature.auth.domain.TelegramLoginState
import uz.mahalla.testutil.FakeDeviceInfoProvider
import uz.mahalla.testutil.FakePinStorage
import uz.mahalla.testutil.FakeRequestLocationProvider
import uz.mahalla.testutil.FakeSessionStore
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * `AuthRepository` на MockWebServer: запрос кода, верификация, обновление и
 * выход по контракту бэкенда Mahalla (issue #42) — пути `auth/send-otp` и
 * `auth/verify-otp`, конверт `{success, data, error}`, обязательные
 * устройство и координаты.
 *
 * Клиент собирается тем же [NetworkFactory], что и в проде — тест проверяет
 * production-конфигурацию, а не свою копию. Часы фиксированные: срок жизни
 * токена обязан быть детерминированным.
 */
class AuthRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var sessionStore: FakeSessionStore
    private lateinit var pinStorage: FakePinStorage

    private val deviceInfoProvider = FakeDeviceInfoProvider()
    private val locationProvider = FakeRequestLocationProvider(
        DeviceLocation(latitude = 41.31, longitude = 69.24),
    )

    private val clock: Clock =
        Clock.fixed(Instant.ofEpochSecond(FIXED_NOW_EPOCH_SECONDS), ZoneOffset.UTC)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        sessionStore = FakeSessionStore()
        pinStorage = FakePinStorage(initialPin = "1234")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `request code sends the phone, the device and the coordinates`() = runTest {
        server.enqueue(
            envelope(
                """{"otpToken":"otp-1","expiresInSeconds":120,"cooldownSeconds":30,
                   "maskedPhone":"+998 ** *** 45 67","channel":"SMS"}""",
            ),
        )

        val result = repository().requestCode("+998901234567")

        assertEquals(
            ApiResult.Success(
                OtpChallenge(
                    otpToken = "otp-1",
                    codeLength = OtpChallenge.DEFAULT_CODE_LENGTH,
                    resendAfterSeconds = 30,
                    expiresInSeconds = 120,
                ),
            ),
            result,
        )

        val request = server.takeRequest()
        assertEquals("/auth/send-otp", request.path)
        val body = request.bodyJson()
        assertEquals("+998901234567", body["phone"]?.jsonPrimitive?.content)
        // Без координат и устройства бэкенд отвечает 400 VALIDATION_ERROR
        // («Joylashuv ruxsatini yoqing») — ровно тем, с чего началась задача.
        assertEquals("41.31", body["lat"]?.jsonPrimitive?.content)
        assertEquals("69.24", body["lng"]?.jsonPrimitive?.content)
        val device = body["device"]!!.jsonObject
        assertEquals("device-1", device["deviceId"]?.jsonPrimitive?.content)
        assertEquals("ANDROID", device["platform"]?.jsonPrimitive?.content)
        assertEquals("Pixel 8", device["deviceName"]?.jsonPrimitive?.content)
    }

    @Test
    fun `missing challenge fields fall back to client defaults`() = runTest {
        server.enqueue(envelope("""{"otpToken":"otp-1"}"""))

        val result = repository().requestCode("+998901234567")

        assertEquals(ApiResult.Success(OtpChallenge(otpToken = "otp-1")), result)
    }

    @Test
    fun `nonsense challenge values are replaced by defaults`() = runTest {
        // Ноль секунд жизни кода — это не «мгновенно», а мусор, на котором
        // экран OTP собрать нельзя.
        server.enqueue(
            envelope("""{"otpToken":"otp-1","cooldownSeconds":-5,"expiresInSeconds":0}"""),
        )

        val challenge = (repository().requestCode("+998901234567") as ApiResult.Success).data

        assertEquals(OtpChallenge(otpToken = "otp-1"), challenge)
    }

    @Test
    fun `response without an otp token is unusable`() = runTest {
        // Проверять код было бы нечем: verify-otp принимает токен, а не номер.
        server.enqueue(envelope("""{"expiresInSeconds":120}"""))

        val result = repository().requestCode("+998901234567")

        assertEquals(ApiError.Serialization, (result as ApiResult.Failure).error)
    }

    @Test
    fun `validation error carries the backend message`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"VALIDATION_ERROR",
                       "message":"Joylashuv ruxsatini yoqing"}}""",
                ),
        )

        val result = repository().requestCode("+998901234567")

        val failure = (result as ApiResult.Failure).failure
        assertEquals(400, (failure.error as ApiError.Http).code)
        assertEquals("VALIDATION_ERROR", failure.server?.code)
        assertEquals("Joylashuv ruxsatini yoqing", failure.serverMessage)
    }

    @Test
    fun `envelope failure with a successful http code is still a failure`() = runTest {
        // Тот же конверт, но код 200: без разбора `success` это выглядело бы
        // успехом с пустыми данными.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"SMS_LIMIT","message":"Keyinroq urinib ko'ring"}}""",
                ),
        )

        val result = repository().requestCode("+998901234567")

        val failure = (result as ApiResult.Failure).failure
        assertEquals(ApiError.Business("SMS_LIMIT"), failure.error)
        assertEquals("Keyinroq urinib ko'ring", failure.serverMessage)
    }

    @Test
    fun `rate limited request code reports the http error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))

        val result = repository().requestCode("+998901234567")

        val error = (result as ApiResult.Failure).error
        assertEquals(429, (error as ApiError.Http).code)
    }

    @Test
    fun `verify sends the otp token and saves the session with an absolute expiry`() = runTest {
        server.enqueue(
            envelope(
                """{"sessionId":"s-1","nextStep":"SETUP_PIN",
                   "tokens":{"accessToken":"a-1","refreshToken":"r-1","accessExpiresIn":3600},
                   "user":{"id":"u-1","phone":"+998901234567"}}""",
            ),
        )

        val result = repository().verifyCode("otp-1", "123456")

        // Профиль не заполнен — дальше предлагается его завести.
        assertEquals(ApiResult.Success(LoginResult(isNewUser = true)), result)
        assertEquals(
            Session("a-1", "r-1", FIXED_NOW_EPOCH_SECONDS + 3600, sessionId = "s-1"),
            sessionStore.current(),
        )

        val request = server.takeRequest()
        assertEquals("/auth/verify-otp", request.path)
        val body = request.bodyJson()
        assertEquals("otp-1", body["otpToken"]?.jsonPrimitive?.content)
        assertEquals("123456", body["otpCode"]?.jsonPrimitive?.content)
        assertEquals("device-1", body["device"]!!.jsonObject["deviceId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `filled profile means the user is not new`() = runTest {
        server.enqueue(
            envelope(
                """{"tokens":{"accessToken":"a-1","refreshToken":"r-1"},
                   "user":{"fullName":"Alisher"}}""",
            ),
        )

        val result = repository().verifyCode("otp-1", "123456")

        assertEquals(ApiResult.Success(LoginResult(isNewUser = false)), result)
    }

    @Test
    fun `verify without expiry leaves it unknown`() = runTest {
        server.enqueue(envelope("""{"tokens":{"accessToken":"a-1","refreshToken":"r-1"}}"""))

        repository().verifyCode("otp-1", "123456")

        // Ноль означал бы «истёк в 1970», т.е. вечно просроченный токен.
        assertEquals(Session.UNKNOWN_EXPIRY, sessionStore.current()?.expiresAtEpochSeconds)
    }

    @Test
    fun `verify without tokens is not a login`() = runTest {
        server.enqueue(envelope("""{"sessionId":"s-1","nextStep":"ENTER_PIN"}"""))

        val result = repository().verifyCode("otp-1", "123456")

        assertEquals(ApiError.Serialization, (result as ApiResult.Failure).error)
        assertNull(sessionStore.current())
    }

    @Test
    fun `wrong code neither saves a session nor touches the pin`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody("""{"success":false,"error":{"code":"OTP_INVALID"}}"""),
        )

        val result = repository().verifyCode("otp-1", "000000")

        assertEquals("OTP_INVALID", (result as ApiResult.Failure).failure.server?.code)
        assertNull(sessionStore.current())
        assertEquals("1234", pinStorage.storedPin)
    }

    @Test
    fun `refresh without a session does not hit the network`() = runTest {
        val result = repository().refresh()

        assertEquals(ApiError.Unauthorized, (result as ApiResult.Failure).error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `refresh rotates the token pair and carries the device`() = runTest {
        sessionStore.save(Session("stale", "r-1", sessionId = "s-1"))
        server.enqueue(
            envelope(
                """{"sessionId":"s-1",
                   "tokens":{"accessToken":"fresh","refreshToken":"r-2","accessExpiresIn":60}}""",
            ),
        )

        val result = repository().refresh()

        assertEquals(ApiResult.Success(Unit), result)
        assertEquals(
            Session("fresh", "r-2", FIXED_NOW_EPOCH_SECONDS + 60, sessionId = "s-1"),
            sessionStore.current(),
        )

        val request = server.takeRequest()
        assertEquals("/auth/refresh", request.path)
        val body = request.bodyJson()
        assertEquals("r-1", body["refreshToken"]?.jsonPrimitive?.content)
        assertEquals("device-1", body["device"]!!.jsonObject["deviceId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `dead refresh token clears the session`() = runTest {
        sessionStore.save(Session("stale", "r-1"))
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody("""{"success":false,"error":{"code":"TOKEN_INVALID"}}"""),
        )

        val result = repository().refresh()

        assertEquals(ApiError.Unauthorized, (result as ApiResult.Failure).error)
        assertNull("сессию с мёртвым refresh хранить нечего", sessionStore.current())
    }

    @Test
    fun `refresh keeps the session when the server is broken`() = runTest {
        sessionStore.save(Session("stale", "r-1"))
        server.enqueue(MockResponse().setResponseCode(500))

        repository().refresh()

        // 5xx — проблема сервера, а не токена: разлогинивать за это нельзя.
        assertEquals(Session("stale", "r-1"), sessionStore.current())
    }

    @Test
    fun `logout names the session in the header and wipes local data`() = runTest {
        sessionStore.save(Session("a-1", "r-1", sessionId = "s-1"))
        server.enqueue(envelope("{}"))

        repository().logout()

        val request = server.takeRequest()
        assertEquals("/auth/logout?allDevices=false", request.path)
        assertEquals("s-1", request.getHeader("X-Session-Id"))
        assertNull(sessionStore.current())
        assertNull("PIN защищал прошлую сессию", pinStorage.storedPin)
    }

    @Test
    fun `logout wipes local data even when the request fails`() = runTest {
        sessionStore.save(Session("a-1", "r-1"))
        server.enqueue(MockResponse().setResponseCode(500))

        repository().logout()

        assertNull(sessionStore.current())
        assertNull(pinStorage.storedPin)
    }

    @Test
    fun `logout without a session does not call the server`() = runTest {
        repository().logout()

        assertEquals(0, server.requestCount)
        assertNull(pinStorage.storedPin)
    }

    @Test
    fun `authorized flag follows the stored session`() = runTest {
        val repository = repository()
        server.enqueue(envelope("""{"tokens":{"accessToken":"a-1","refreshToken":"r-1"}}"""))

        repository.verifyCode("otp-1", "123456")

        assertTrue(sessionStore.current() != null)
    }

    // --- Вход через Telegram-бот (issue #46) ---

    @Test
    fun `telegram init asks for a link and sends no phone number`() = runTest {
        server.enqueue(
            envelope(
                """{"deepLinkToken":"dl-1",
                   "telegramBotUrl":"https://t.me/MahallaVerifyBot?start=dl-1",
                   "expiresInSeconds":300}""",
            ),
        )

        val result = repository().startTelegramLogin()

        val challenge = (result as ApiResult.Success).data
        assertEquals("dl-1", challenge.deepLinkToken)
        assertEquals("https://t.me/MahallaVerifyBot?start=dl-1", challenge.botUrl)
        assertEquals(300, challenge.expiresInSeconds)

        val request = server.takeRequest()
        assertEquals("/auth/telegram/init", request.path)
        val body = request.bodyJson()
        assertNull("номер телефона на этом шаге неизвестен", body["phone"])
        assertEquals(
            FakeDeviceInfoProvider.DEFAULT.deviceId,
            body["device"]!!.jsonObject["deviceId"]!!.jsonPrimitive.content,
        )
        assertEquals(41.31, body["lat"]!!.jsonPrimitive.content.toDouble(), 0.001)
    }

    /**
     * Ссылка, ведущая не в Telegram, — отказ, а не экран-тупик: открыть её мы
     * всё равно откажемся (`TelegramBotLink`).
     */
    @Test
    fun `telegram init rejects a foreign bot link`() = runTest {
        server.enqueue(
            envelope("""{"deepLinkToken":"dl-1","telegramBotUrl":"https://evil.example/x"}"""),
        )

        val result = repository().startTelegramLogin()

        assertEquals(ApiError.Serialization, (result as ApiResult.Failure).error)
    }

    @Test
    fun `TG_PENDING is reported as waiting, not as an error`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"TG_PENDING",
                       "message":"Telegram bot orqali tasdiqlash kutilmoqda."}}""",
                ),
        )

        val result = repository().checkTelegramLogin("dl-1")

        assertEquals(
            TelegramLoginState.Pending,
            (result as ApiResult.Success).data,
        )
        assertNull("сессии пока нет", sessionStore.current())

        val body = server.takeRequest().bodyJson()
        assertEquals("dl-1", body["deepLinkToken"]!!.jsonPrimitive.content)
    }

    /**
     * Токены у этого эндпоинта лежат в корне ответа, а не в `tokens`, и
     * `sessionId` бэкенд не отдаёт вовсе.
     */
    @Test
    fun `confirmed telegram login saves the session`() = runTest {
        server.enqueue(
            envelope(
                """{"accessToken":"a-tg","refreshToken":"r-tg","accessExpiresIn":3600,
                   "refreshExpiresIn":86400,"requiresPhoneVerify":false,
                   "user":{"id":"u-1","phone":"+998901234567","fullName":"Ali"}}""",
            ),
        )

        val result = repository().checkTelegramLogin("dl-1")

        val state = (result as ApiResult.Success).data
        assertEquals(
            TelegramLoginState.Confirmed(
                login = LoginResult(isNewUser = false),
                // Номер нужен только когда его просят подтвердить — здесь он
                // ни на что не влияет и в состояние не едет.
                phone = null,
            ),
            state,
        )
        assertEquals(
            Session(
                accessToken = "a-tg",
                refreshToken = "r-tg",
                expiresAtEpochSeconds = FIXED_NOW_EPOCH_SECONDS + 3600,
                sessionId = null,
            ),
            sessionStore.current(),
        )
    }

    /**
     * Telegram подтвердил личность, но номер не проверен. Сессию не сохраняем:
     * отличить такую «половину входа» от полноценной в остальном приложении
     * было бы нечем.
     */
    @Test
    fun `unverified phone does not create a session`() = runTest {
        server.enqueue(
            envelope(
                """{"accessToken":"a-tg","refreshToken":"r-tg","accessExpiresIn":3600,
                   "requiresPhoneVerify":true,
                   "user":{"id":"u-1","phone":"+998937555505"}}""",
            ),
        )

        val result = repository().checkTelegramLogin("dl-1")

        assertEquals(
            TelegramLoginState.Confirmed(
                login = LoginResult(isNewUser = true),
                requiresPhoneVerify = true,
                // Номер бот уже сообщил бэкенду — экран назовёт его человеку
                // (issue #49).
                phone = "+998937555505",
            ),
            (result as ApiResult.Success).data,
        )
        assertNull("полуавторизованной сессии быть не должно", sessionStore.current())
    }

    @Test
    fun `a real telegram failure stays a failure`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody("""{"success":false,"error":{"code":"VALIDATION_ERROR"}}"""),
        )

        val result = repository().checkTelegramLogin("dl-1")

        assertTrue(result is ApiResult.Failure)
        assertEquals(
            "VALIDATION_ERROR",
            (result as ApiResult.Failure).failure.server?.code,
        )
    }

    private fun repository(): AuthRepository = DefaultAuthRepository(
        authApi = authApi(),
        sessionStore = sessionStore,
        pinStorage = pinStorage,
        deviceInfoProvider = deviceInfoProvider,
        locationProvider = locationProvider,
        clock = clock,
    )

    /** Тот же «голый» клиент, что и `@RefreshClient` в проде. */
    private fun authApi(): AuthApi = NetworkFactory.retrofit(
        baseUrl = server.url("/").toString(),
        client = NetworkFactory.clientBuilder().build(),
        converterFactory = NetworkFactory.converterFactory(NetworkFactory.json()),
    ).create(AuthApi::class.java)

    /** Успешный ответ в конверте бэкенда: полезная нагрузка лежит в `data`. */
    private fun envelope(data: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody("""{"success":true,"data":$data}""")

    private fun RecordedRequest.bodyJson(): JsonObject =
        Json.parseToJsonElement(body.readUtf8()).jsonObject

    private companion object {
        const val FIXED_NOW_EPOCH_SECONDS = 1_774_000_000L
    }
}
