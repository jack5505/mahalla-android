package uz.mahalla.feature.security.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import uz.mahalla.data.network.auth.SessionApi
import uz.mahalla.data.network.pin.PinApi
import uz.mahalla.data.prefs.Session
import uz.mahalla.testutil.FakeDeviceInfoProvider
import uz.mahalla.testutil.FakeOnboardingRepository
import uz.mahalla.testutil.FakePinStorage
import uz.mahalla.testutil.FakeRequestLocationProvider
import uz.mahalla.testutil.FakeSessionStore
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Аккаунтный PIN и продолжение сессии (issue #102) на настоящем сетевом стеке
 * ([NetworkFactory] + [MockWebServer]): подмена Retrofit фейком не поймала бы
 * ни ошибку в пути запроса, ни несовпадение схемы JSON.
 *
 * Контракт снят со стенда: `GET pin/status` требует `deviceId` в query,
 * `PUT pin/change` — тело из трёх полей, `PUT pin/biometric` — ещё и PIN,
 * `POST auth/pin-resume` — устройство с координатами. Все ручки требуют
 * Bearer: без токена стенд отвечает `401 UNAUTHORIZED`.
 *
 * Главный инвариант задачи проверяется здесь же: **локальный хэш пишется
 * только кодом, который сервер принял**.
 */
class SecurityRepositoryTest {

    private lateinit var server: MockWebServer
    private val pinStorage = FakePinStorage(initialPin = "111111")
    private val onboarding = FakeOnboardingRepository()
    private val sessionStore = FakeSessionStore(Session("old-access", "old-refresh", sessionId = "s-1"))

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
    fun `status asks about this device and maps the envelope`() = runTest {
        server.enqueue(
            envelope(
                """{"pinSet":true,"biometricEnabled":true,"lockedSecondsRemaining":30,
                   "pinChangedAt":"2026-09-01T10:00:00","lastUsedAt":"2026-09-04T09:00:00"}""",
            ),
        )

        val status = (repository().pinStatus() as ApiResult.Success).data

        assertEquals("/pin/status?deviceId=device-1", server.takeRequest().path)
        assertTrue(status.pinSet)
        assertTrue(status.biometricEnabled)
        assertTrue(status.locked)
        assertEquals(30L, status.lockedSecondsRemaining)
    }

    @Test
    fun `status without flags is not an error`() = runTest {
        server.enqueue(envelope("""{}"""))

        val status = (repository().pinStatus() as ApiResult.Success).data

        assertFalse(status.pinSet)
        assertFalse(status.locked)
    }

    @Test
    fun `change sends both codes and the device`() = runTest {
        server.enqueue(envelope("null"))

        val result = repository().changePin(currentPin = "111111", newPin = "222222")

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/pin/change", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("111111", body["currentPin"]?.jsonPrimitive?.content)
        assertEquals("222222", body["newPin"]?.jsonPrimitive?.content)
        assertEquals("device-1", body["deviceId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `accepted change rewrites the local copy`() = runTest {
        server.enqueue(envelope("null"))

        repository().changePin(currentPin = "111111", newPin = "222222")

        // Иначе экран блокировки продолжал бы принимать старый код, а сервер —
        // новый: разъехавшиеся PIN человек не различит.
        assertEquals("222222", pinStorage.storedPin)
    }

    @Test
    fun `refused change leaves the local copy alone`() = runTest {
        server.enqueue(
            failure("PIN_INVALID", "Joriy PIN-kod noto'g'ri. 2 urinish qoldi"),
        )

        val failure = (repository().changePin("999999", "222222") as ApiResult.Failure).failure

        assertEquals(ApiError.Business("PIN_INVALID"), failure.error)
        assertEquals("Joriy PIN-kod noto'g'ri. 2 urinish qoldi", failure.serverMessage)
        assertEquals("111111", pinStorage.storedPin)
    }

    @Test
    fun `storage failure after an accepted change clears the local copy`() = runTest {
        server.enqueue(envelope("null"))
        val failing = FakePinStorage(initialPin = "111111")
            .apply { saveFailure = IOException("keystore") }

        // Смена состоялась — сервер сказал «да», и врать об этом нельзя.
        val result = repository(pinStorage = failing).changePin("111111", "222222")

        assertTrue(result is ApiResult.Success)
        // Но прежний код в хранилище остаться не имеет права: он открывал бы
        // замок кодом, которого бэкенд больше не знает. Без локальной копии
        // app-lock просто не вооружается — это хуже замка, но лучше замка,
        // который открывается отменённым PIN'ом.
        assertEquals(1, failing.clearCount)
        assertNull(failing.storedPin)
    }

    @Test
    fun `malformed codes never reach the network`() = runTest {
        val repository = repository()

        val short = repository.changePin("111111", "22222")
        val letters = repository.changePin("111111", "22222a")

        assertEquals(
            ApiError.Business(DefaultSecurityRepository.INVALID_PIN_CODE),
            (short as ApiResult.Failure).error,
        )
        assertEquals(
            ApiError.Business(DefaultSecurityRepository.INVALID_PIN_CODE),
            (letters as ApiResult.Failure).error,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `changing a pin to itself never reaches the network`() = runTest {
        val result = repository().changePin("111111", "111111")

        assertEquals(
            ApiError.Business(DefaultSecurityRepository.SAME_PIN_CODE),
            (result as ApiResult.Failure).error,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `biometric toggle carries the pin the backend demands`() = runTest {
        server.enqueue(envelope("true"))

        val enabled = (repository().setBiometricEnabled(true, "111111") as ApiResult.Success).data

        assertTrue(enabled)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/pin/biometric", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(true, body["enabled"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("111111", body["pin"]?.jsonPrimitive?.content)
        assertEquals("device-1", body["deviceId"]?.jsonPrimitive?.content)
        assertEquals(listOf(true), onboarding.biometricWrites)
    }

    @Test
    fun `switching biometric off keeps the flag in the body`() = runTest {
        server.enqueue(envelope("false"))

        val enabled = (repository().setBiometricEnabled(false, "111111") as ApiResult.Success).data

        assertFalse(enabled)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        // Без явного значения kotlinx.serialization выбросила бы поле, равное
        // дефолту, и бэкенд получил бы запрос без флага.
        assertEquals(false, body["enabled"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(listOf(false), onboarding.biometricWrites)
    }

    @Test
    fun `silent toggle answer is read as the requested value`() = runTest {
        server.enqueue(envelope("null"))

        val enabled = (repository().setBiometricEnabled(true, "111111") as ApiResult.Success).data

        // `ensureSuccess` уже подтвердил, что переключение состоялось: пустое
        // `data` — не отказ.
        assertTrue(enabled)
    }

    @Test
    fun `refused toggle keeps the local flag untouched`() = runTest {
        server.enqueue(failure("PIN_INVALID", "PIN-kod noto'g'ri"))

        val result = repository().setBiometricEnabled(true, "111111")

        assertEquals(ApiError.Business("PIN_INVALID"), (result as ApiResult.Failure).error)
        assertTrue(onboarding.biometricWrites.isEmpty())
    }

    @Test
    fun `session check maps the envelope`() = runTest {
        server.enqueue(
            envelope("""{"sessionValid":true,"pinRequired":true,"reason":"IDLE_TIMEOUT"}"""),
        )

        val check = (repository().checkSession() as ApiResult.Success).data

        val request = server.takeRequest()
        assertEquals("/auth/session/check", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(
            "device-1",
            body["device"]?.jsonObject?.get("deviceId")?.jsonPrimitive?.content,
        )
        assertTrue(check.valid)
        assertTrue(check.pinRequired)
        assertEquals("IDLE_TIMEOUT", check.reason)
    }

    @Test
    fun `resume sends the pin with device and coordinates`() = runTest {
        server.enqueue(
            envelope(
                """{"tokens":{"accessToken":"new-access","refreshToken":"new-refresh",
                   "accessExpiresIn":900},"sessionId":"s-2"}""",
            ),
        )

        val result = repository().resumeSession("111111")

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("/auth/pin-resume", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("111111", body["pin"]?.jsonPrimitive?.content)
        assertEquals(41.311081, body["lat"]?.jsonPrimitive?.content?.toDouble()!!, 1e-6)
        assertEquals(
            "device-1",
            body["device"]?.jsonObject?.get("deviceId")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `resume stores the refreshed pair`() = runTest {
        server.enqueue(
            envelope(
                """{"tokens":{"accessToken":"new-access","refreshToken":"new-refresh",
                   "accessExpiresIn":900},"sessionId":"s-2"}""",
            ),
        )

        repository().resumeSession("222222")

        val session = sessionStore.current()!!
        assertEquals("new-access", session.accessToken)
        assertEquals("s-2", session.sessionId)
        assertEquals(NOW.epochSecond + 900, session.expiresAtEpochSeconds)
        // Сервер принял код — значит он и есть аккаунтный PIN.
        assertEquals("222222", pinStorage.storedPin)
    }

    @Test
    fun `resume without tokens keeps the session and still unlocks`() = runTest {
        server.enqueue(envelope("""{"sessionId":"s-1"}"""))

        val result = repository().resumeSession("222222")

        // Роняя здесь, мы запирали бы человека за экраном блокировки при
        // живой сессии: прежняя пара всё ещё в хранилище и всё ещё рабочая.
        assertTrue(result is ApiResult.Success)
        assertEquals("old-access", sessionStore.current()!!.accessToken)
    }

    @Test
    fun `refused resume leaves the local copy alone`() = runTest {
        server.enqueue(failure("PIN_INVALID", "PIN-kod noto'g'ri"))

        val result = repository().resumeSession("999999")

        assertEquals(ApiError.Business("PIN_INVALID"), (result as ApiResult.Failure).error)
        assertEquals("111111", pinStorage.storedPin)
    }

    @Test
    fun `two hundred with success false is a refusal`() = runTest {
        server.enqueue(failure("PIN_LOCKED", "PIN-kod bloklangan"))

        val result = repository().pinStatus()

        assertEquals(ApiError.Business("PIN_LOCKED"), (result as ApiResult.Failure).error)
    }

    @Test
    fun `expired token is reported as unauthorized`() = runTest {
        // Так стенд отвечает на все ручки задачи без Bearer.
        server.enqueue(MockResponse().setResponseCode(401))

        val result = repository().pinStatus()

        assertEquals(ApiError.Unauthorized, (result as ApiResult.Failure).error)
        assertNull((result as ApiResult.Failure).failure.serverMessage)
    }

    private fun repository(pinStorage: FakePinStorage = this.pinStorage): DefaultSecurityRepository {
        val retrofit = NetworkFactory.retrofit(
            server.url("/").toString(),
            NetworkFactory.clientBuilder().build(),
            NetworkFactory.converterFactory(NetworkFactory.json()),
        )
        return DefaultSecurityRepository(
            pinApi = retrofit.create(PinApi::class.java),
            sessionApi = retrofit.create(SessionApi::class.java),
            sessionStore = sessionStore,
            onboardingRepository = onboarding,
            pinStorage = pinStorage,
            deviceInfoProvider = FakeDeviceInfoProvider(),
            locationProvider = FakeRequestLocationProvider(),
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
        )
    }

    private fun envelope(data: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody("""{"success":true,"data":$data}""")

    private fun failure(code: String, message: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody("""{"success":false,"error":{"code":"$code","message":"$message"}}""")

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-04T12:00:00Z")
    }
}
