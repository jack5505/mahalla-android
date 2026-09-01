package uz.mahalla.feature.update.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uz.mahalla.BuildConfig
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.feature.update.domain.UpdateDecision
import uz.mahalla.feature.update.domain.UpdatePolicy

/**
 * Проверка версии (issue #80) на настоящем сетевом стеке ([NetworkFactory] +
 * [MockWebServer]): подмена Retrofit фейком не поймала бы ни ошибку в пути
 * запроса, ни несовпадение схемы JSON.
 *
 * Контракт снят со стенда: `POST app/version/check` → `VersionCheckResponse` в
 * общем конверте, `POST app/version/skip` → конверт без полезной нагрузки.
 */
class AppVersionRepositoryTest {

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
    fun `the check reports the platform and the version of this build`() = runTest {
        server.enqueue(envelope("""{"updateAvailable":false,"updateRequired":false}"""))

        repository().check()

        val request = server.takeRequest()
        assertEquals("/app/version/check", request.path)
        assertEquals("POST", request.method)
        val body = request.body.readUtf8()
        assertTrue(body, body.contains(""""platform":"ANDROID""""))
        assertTrue(body, body.contains(""""currentVersionCode":${BuildConfig.VERSION_CODE}"""))
        assertTrue(body, body.contains(""""currentVersionName":"${BuildConfig.VERSION_NAME}""""))
    }

    @Test
    fun `an empty answer of the stand means nothing to show`() = runTest {
        // Ровно то, что отвечает стенд с незаполненным реестром версий:
        // флаги false, всё остальное null.
        server.enqueue(
            envelope(
                """{"updateAvailable":false,"updateRequired":false,"policy":null,
                   "latestVersionName":null,"latestVersionCode":null,"releaseNotes":null,
                   "storeUrl":null,"remainingSkips":null,"versionId":null}""",
            ),
        )

        val decision = (repository().check() as ApiResult.Success).data

        assertEquals(UpdateDecision.None, decision)
    }

    @Test
    fun `a required update arrives with everything the screen shows`() = runTest {
        server.enqueue(
            envelope(
                """{"updateAvailable":true,"updateRequired":true,"policy":"IMMEDIATE",
                   "latestVersionName":"1.4.0","latestVersionCode":14,
                   "releaseNotes":"Karta tezroq ochiladi","versionId":"v-1",
                   "storeUrl":"https://play.google.com/store/apps/details?id=uz.mahalla",
                   "remainingSkips":0}""",
            ),
        )

        val decision = (repository().check() as ApiResult.Success).data

        val update = (decision as UpdateDecision.Required).update
        assertEquals("v-1", update.versionId)
        assertEquals("1.4.0", update.versionName)
        assertEquals(14, update.versionCode)
        assertEquals("Karta tezroq ochiladi", update.releaseNotes)
        assertEquals(UpdatePolicy.Immediate, update.policy)
        assertEquals(
            "https://play.google.com/store/apps/details?id=uz.mahalla",
            update.storeUrl,
        )
    }

    @Test
    fun `an available update with skips left is suggested`() = runTest {
        server.enqueue(
            envelope(
                """{"updateAvailable":true,"updateRequired":false,"policy":"FLEXIBLE",
                   "latestVersionName":"1.4.0","versionId":"v-1","remainingSkips":3}""",
            ),
        )

        val decision = (repository().check() as ApiResult.Success).data

        assertEquals(3, (decision as UpdateDecision.Suggested).update.remainingSkips)
    }

    @Test
    fun `a refused store link falls back to our own store page`() = runTest {
        // Блокирующий экран без единой рабочей кнопки был бы тупиком, а
        // открывать чужой intent по слову сервера нельзя.
        server.enqueue(
            envelope(
                """{"updateAvailable":true,"updateRequired":true,
                   "storeUrl":"mahalla://place/42"}""",
            ),
        )

        val decision = (repository().check() as ApiResult.Success).data

        assertEquals(
            "https://play.google.com/store/apps/details?id=${BuildConfig.APPLICATION_ID}",
            (decision as UpdateDecision.Required).update.storeUrl,
        )
    }

    @Test
    fun `blank strings do not reach the screen`() = runTest {
        server.enqueue(
            envelope(
                """{"updateAvailable":true,"updateRequired":true,
                   "latestVersionName":"  ","releaseNotes":"","versionId":" "}""",
            ),
        )

        val update = ((repository().check() as ApiResult.Success).data
            as UpdateDecision.Required).update

        assertEquals(null, update.versionName)
        assertEquals(null, update.releaseNotes)
        assertEquals(null, update.versionId)
    }

    @Test
    fun `a 2xx with success false is a failure, not a silent update`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody("""{"success":false,"error":{"code":"GEO_PERMISSION_REQUIRED"}}"""),
        )

        val result = repository().check()

        assertEquals(
            ApiError.Business("GEO_PERMISSION_REQUIRED"),
            (result as ApiResult.Failure).error,
        )
    }

    @Test
    fun `the skip names the version of the backend registry`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody("""{"success":true}"""),
        )

        val result = repository().skip("v-1")

        val request = server.takeRequest()
        assertEquals("/app/version/skip", request.path)
        assertEquals("""{"versionId":"v-1"}""", request.body.readUtf8())
        assertTrue(result is ApiResult.Success)
    }

    @Test
    fun `the skip fails without a session, as the backend requires one`() = runTest {
        // До входа пропуски считать некому: бэкенд отвечает 401 (проверено).
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody("""{"success":false,"error":{"code":"UNAUTHORIZED"}}"""),
        )

        val result = repository().skip("v-1")

        assertEquals(ApiError.Unauthorized, (result as ApiResult.Failure).error)
    }

    private fun repository() = DefaultAppVersionRepository(
        NetworkFactory
            .retrofit(
                server.url("/").toString(),
                NetworkFactory.clientBuilder().build(),
                NetworkFactory.converterFactory(NetworkFactory.json()),
            )
            .create(AppVersionApi::class.java),
    )

    private fun envelope(data: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody("""{"success":true,"data":$data}""")
}
