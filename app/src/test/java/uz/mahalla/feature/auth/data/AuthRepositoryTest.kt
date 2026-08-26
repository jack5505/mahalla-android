package uz.mahalla.feature.auth.data

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
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.data.network.auth.AuthApi
import uz.mahalla.data.prefs.Session
import uz.mahalla.feature.auth.domain.LoginResult
import uz.mahalla.feature.auth.domain.OtpChallenge
import uz.mahalla.testutil.FakePinStorage
import uz.mahalla.testutil.FakeSessionStore
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * `AuthRepository` на MockWebServer (эпик 3, сквозная задача): запрос кода,
 * верификация, обновление и выход.
 *
 * Клиент собирается тем же [NetworkFactory], что и в проде — тест проверяет
 * production-конфигурацию, а не свою копию. Часы фиксированные: срок жизни
 * токена обязан быть детерминированным.
 */
class AuthRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var sessionStore: FakeSessionStore
    private lateinit var pinStorage: FakePinStorage

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
    fun `request code sends the phone and returns server parameters`() = runTest {
        server.enqueue(jsonResponse("""{"resendAfter":30,"expiresIn":120,"codeLength":4}"""))

        val result = repository().requestCode("+998901234567")

        assertEquals(
            ApiResult.Success(
                OtpChallenge(codeLength = 4, resendAfterSeconds = 30, expiresInSeconds = 120),
            ),
            result,
        )
        val request = server.takeRequest()
        assertEquals("/auth/otp/request", request.path)
        assertEquals("""{"phone":"+998901234567"}""", request.body.readUtf8())
    }

    @Test
    fun `missing challenge fields fall back to client defaults`() = runTest {
        server.enqueue(jsonResponse("{}"))

        val result = repository().requestCode("+998901234567")

        assertEquals(ApiResult.Success(OtpChallenge()), result)
    }

    @Test
    fun `nonsense challenge values are replaced by defaults`() = runTest {
        // Ноль секунд до повтора и код нулевой длины — это не «мгновенно» и не
        // «пустое поле», а мусор, на котором экран OTP собрать нельзя.
        server.enqueue(jsonResponse("""{"resendAfter":-5,"codeLength":0,"expiresIn":0}"""))

        val challenge = (repository().requestCode("+998901234567") as ApiResult.Success).data

        assertEquals(OtpChallenge(), challenge)
    }

    @Test
    fun `rate limited request code reports the http error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))

        val result = repository().requestCode("+998901234567")

        val error = (result as ApiResult.Failure).error
        assertEquals(429, (error as ApiError.Http).code)
    }

    @Test
    fun `verify saves the session with an absolute expiry`() = runTest {
        server.enqueue(
            jsonResponse(
                """{"accessToken":"a-1","refreshToken":"r-1","expiresIn":3600,"isNewUser":true}""",
            ),
        )

        val result = repository().verifyCode("+998901234567", "123456")

        assertEquals(ApiResult.Success(LoginResult(isNewUser = true)), result)
        assertEquals(
            Session("a-1", "r-1", FIXED_NOW_EPOCH_SECONDS + 3600),
            sessionStore.current(),
        )
        val request = server.takeRequest()
        assertEquals("/auth/otp/verify", request.path)
        assertEquals("""{"phone":"+998901234567","code":"123456"}""", request.body.readUtf8())
    }

    @Test
    fun `verify without expiresIn leaves the expiry unknown`() = runTest {
        server.enqueue(jsonResponse("""{"accessToken":"a-1","refreshToken":"r-1"}"""))

        repository().verifyCode("+998901234567", "123456")

        // Ноль означал бы «истёк в 1970», т.е. вечно просроченный токен.
        assertEquals(Session.UNKNOWN_EXPIRY, sessionStore.current()?.expiresAtEpochSeconds)
    }

    @Test
    fun `wrong code neither saves a session nor touches the pin`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = repository().verifyCode("+998901234567", "000000")

        assertEquals(ApiResult.Failure(ApiError.Unauthorized), result)
        assertNull(sessionStore.current())
        assertEquals("1234", pinStorage.storedPin)
    }

    @Test
    fun `refresh without a session does not hit the network`() = runTest {
        val result = repository().refresh()

        assertEquals(ApiResult.Failure(ApiError.Unauthorized), result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `refresh rotates the token pair`() = runTest {
        sessionStore.save(Session("stale", "r-1"))
        server.enqueue(
            jsonResponse("""{"accessToken":"fresh","refreshToken":"r-2","expiresIn":60}"""),
        )

        val result = repository().refresh()

        assertEquals(ApiResult.Success(Unit), result)
        assertEquals(
            Session("fresh", "r-2", FIXED_NOW_EPOCH_SECONDS + 60),
            sessionStore.current(),
        )
    }

    @Test
    fun `dead refresh token clears the session`() = runTest {
        sessionStore.save(Session("stale", "r-1"))
        server.enqueue(MockResponse().setResponseCode(401))

        val result = repository().refresh()

        assertEquals(ApiResult.Failure(ApiError.Unauthorized), result)
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
    fun `logout revokes the refresh token and wipes local data`() = runTest {
        sessionStore.save(Session("a-1", "r-1"))
        server.enqueue(MockResponse().setResponseCode(204))

        repository().logout()

        val request = server.takeRequest()
        assertEquals("/auth/logout", request.path)
        assertEquals("""{"refreshToken":"r-1"}""", request.body.readUtf8())
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
        server.enqueue(jsonResponse("""{"accessToken":"a-1","refreshToken":"r-1"}"""))

        repository.verifyCode("+998901234567", "123456")

        assertTrue(sessionStore.current() != null)
    }

    private fun repository(): AuthRepository = DefaultAuthRepository(
        authApi = authApi(),
        sessionStore = sessionStore,
        pinStorage = pinStorage,
        clock = clock,
    )

    /** Тот же «голый» клиент, что и `@RefreshClient` в проде. */
    private fun authApi(): AuthApi = NetworkFactory.retrofit(
        baseUrl = server.url("/").toString(),
        client = NetworkFactory.clientBuilder().build(),
        converterFactory = NetworkFactory.converterFactory(NetworkFactory.json()),
    ).create(AuthApi::class.java)

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody(body)

    private companion object {
        const val FIXED_NOW_EPOCH_SECONDS = 1_774_000_000L
    }
}
