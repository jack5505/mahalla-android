package uz.mahalla.feature.profile.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.data.prefs.Session
import uz.mahalla.feature.profile.domain.DeviceSession
import uz.mahalla.feature.profile.domain.DeviceSessionStatus
import uz.mahalla.testutil.FakeDeviceInfoProvider
import uz.mahalla.testutil.FakeSessionStore
import java.time.Instant

/**
 * Устройства с открытым входом (issue #61) на настоящем сетевом стеке
 * ([NetworkFactory] + [MockWebServer]): подмена Retrofit фейком не поймала бы
 * ни ошибку в пути запроса, ни несовпадение схемы JSON.
 *
 * Контракт снят со стенда: `GET auth/sessions` требует `deviceId` и
 * `platform` в query, `revoke` — тело, `trust` — query `trusted`; ответы
 * приходят в общем конверте, у последних двух — без полезной нагрузки.
 */
class SessionsRepositoryTest {

    private lateinit var server: MockWebServer

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
    fun `sessions ask about this device and map the envelope`() = runTest {
        server.enqueue(
            envelope(
                """[
                  {"sessionId":"s-2","deviceName":"Xiaomi Redmi Note 12","platform":"ANDROID",
                   "appVersion":"1.0","status":"PIN_REQUIRED",
                   "lastActivityAt":"2026-08-28T20:40:00.123","lastIp":"84.54.0.9",
                   "trustedDevice":false,"currentDevice":false},
                  {"sessionId":"s-1","deviceName":"Samsung SM-A536B","platform":"ANDROID",
                   "status":"ACTIVE","lastActivityAt":"2026-08-30T09:12:00Z",
                   "trustedDevice":true,"currentDevice":true}
                ]""",
            ),
        )

        val sessions = (repository().sessions() as ApiResult.Success).data

        val request = server.takeRequest()
        assertEquals(
            "/auth/sessions?deviceId=device-1&platform=ANDROID&osVersion=Android%2014",
            request.path,
        )
        // Своё устройство сверху, дальше по свежести активности.
        assertEquals(listOf("s-1", "s-2"), sessions.map(DeviceSession::id))
        val current = sessions.first()
        assertTrue(current.isCurrent)
        assertTrue(current.trusted)
        assertEquals(DeviceSessionStatus.Active, current.status)
        assertEquals(Instant.parse("2026-08-30T09:12:00Z"), current.lastActivityAt)
        // Jackson отдаёт `LocalDateTime` без зоны — иначе дата пуста у всех.
        assertEquals(Instant.parse("2026-08-28T20:40:00.123Z"), sessions.last().lastActivityAt)
        assertEquals("84.54.0.9", sessions.last().lastIp)
        assertEquals(DeviceSessionStatus.PinRequired, sessions.last().status)
    }

    @Test
    fun `current device is recognised by the stored session id too`() = runTest {
        server.enqueue(
            envelope(
                """[{"sessionId":"s-1","deviceName":"Samsung","currentDevice":false}]""",
            ),
        )

        val sessions = (repository(sessionId = "s-1").sessions() as ApiResult.Success).data

        // Бэкенд не проставил флаг — но эту сессию мы знаем по своей же
        // записи, и предлагать отозвать её нельзя.
        assertTrue(sessions.single().isCurrent)
    }

    @Test
    fun `unusable rows are dropped`() = runTest {
        server.enqueue(
            envelope(
                """[
                  {"deviceName":"Без идентификатора"},
                  {"sessionId":"s-3","deviceName":"Старый вход","status":"REVOKED"},
                  {"sessionId":"s-4","deviceName":"Новый статус","status":"SOMETHING_NEW"}
                ]""",
            ),
        )

        val sessions = (repository().sessions() as ApiResult.Success).data

        // Запись без sessionId нечем отозвать, погашенная сессия — уже не
        // устройство с входом, а незнакомый статус показывать надо.
        assertEquals(listOf("s-4"), sessions.map(DeviceSession::id))
        assertEquals(DeviceSessionStatus.Unknown, sessions.single().status)
    }

    @Test
    fun `revoke names the session and does not touch the rest`() = runTest {
        server.enqueue(envelope("null"))

        val result = repository().revoke("s-2")

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("/auth/sessions/revoke", request.path)
        val body = NetworkFactory.json()
            .parseToJsonElement(request.body.readUtf8())
            .jsonObject
        assertEquals("s-2", body["sessionId"]?.jsonPrimitive?.content)
        // Флаг уходит явно: без него бэкенд полагался бы на свой дефолт.
        assertEquals("false", body["revokeAll"]?.jsonPrimitive?.content)
    }

    @Test
    fun `trust passes the flag in the query`() = runTest {
        server.enqueue(envelope("null"))

        val result = repository().setTrusted("s-2", trusted = true)

        assertTrue(result is ApiResult.Success)
        assertEquals("/auth/sessions/s-2/trust?trusted=true", server.takeRequest().path)
    }

    @Test
    fun `refused revoke reports the server code`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"SESSION_NOT_FOUND",
                       "message":"Sessiya topilmadi"}}""",
                ),
        )

        val failure = (repository().revoke("s-2") as ApiResult.Failure).failure

        // 2xx с `success:false` — отказ, а не успех: иначе экран нарисовал бы
        // отзыв, которого не было.
        assertEquals(ApiError.Business("SESSION_NOT_FOUND"), failure.error)
        assertEquals("Sessiya topilmadi", failure.serverMessage)
    }

    @Test
    fun `expired token is reported as unauthorized`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = repository().sessions()

        assertEquals(ApiError.Unauthorized, (result as ApiResult.Failure).error)
    }

    private fun repository(sessionId: String? = null) = DefaultSessionsRepository(
        sessionsApi = NetworkFactory
            .retrofit(
                server.url("/").toString(),
                NetworkFactory.clientBuilder().build(),
                NetworkFactory.converterFactory(NetworkFactory.json()),
            )
            .create(SessionsApi::class.java),
        deviceInfoProvider = FakeDeviceInfoProvider(),
        sessionStore = FakeSessionStore(
            sessionId?.let { Session("a-1", "r-1", sessionId = it) },
        ),
    )

    private fun envelope(data: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody("""{"success":true,"data":$data}""")
}
