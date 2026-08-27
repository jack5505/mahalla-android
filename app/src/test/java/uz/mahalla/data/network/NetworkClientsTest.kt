package uz.mahalla.data.network

import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uz.mahalla.data.network.tls.CertificatePinSource
import uz.mahalla.testutil.SelfSignedServer
import javax.net.ssl.SSLException

/**
 * Сборка клиентов (issue #30): где в цепочке стоит инспектор трафика.
 *
 * Проверяется именно production-конфигурация — те же `NetworkFactory.mainClient`
 * и `refreshClient`, которыми клиентов собирает `NetworkModule`.
 */
class NetworkClientsTest {

    private lateinit var server: MockWebServer

    /** Заглушки соседей по цепочке: важен порядок, а не их внутренности. */
    private val addressInterceptor = Interceptor { chain ->
        val rewritten = chain.request().url.newBuilder().addQueryParameter(MARKER, "1").build()
        chain.proceed(chain.request().newBuilder().url(rewritten).build())
    }
    private val tokenInterceptor = Interceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .header(AuthInterceptor.HEADER_AUTHORIZATION, "Bearer token")
                .build(),
        )
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `inspector sees the request the way it goes out`() {
        val inspector = RecordingInspector()
        val client = NetworkFactory.mainClient(
            backendUrlInterceptor = addressInterceptor,
            authInterceptor = tokenInterceptor,
            authenticator = Authenticator.NONE,
            inspector = inspector,
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client.newCall(
            Request.Builder()
                .url(server.url("/places?query=osh"))
                .post("""{"city":"tashkent"}""".toRequestBody(JSON))
                .build(),
        ).execute().close()

        val seen = requireNotNull(inspector.request) { "инспектор не получил запрос" }
        // Адрес — уже переписанный (issue #26), иначе в списке был бы виден
        // адрес сборки, а не тот сервер, на который запрос действительно ушёл.
        assertEquals("1", seen.url.queryParameter(MARKER))
        assertEquals("osh", seen.url.queryParameter("query"))
        // Заголовок уже проставлен: инспектор ниже AuthInterceptor'а.
        assertEquals("Bearer token", seen.header(AuthInterceptor.HEADER_AUTHORIZATION))
        assertEquals("""{"city":"tashkent"}""", seen.bodyAsText())
    }

    @Test
    fun `refresh client shows the inspector its requests too`() {
        val inspector = RecordingInspector()
        val client = NetworkFactory.refreshClient(
            backendUrlInterceptor = addressInterceptor,
            inspector = inspector,
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client.newCall(Request.Builder().url(server.url("/auth/otp/request")).build())
            .execute()
            .close()

        val seen = requireNotNull(inspector.request) { "вход и refresh тоже видны" }
        assertEquals("1", seen.url.queryParameter(MARKER))
        // Клиент «голый»: токен ему не подставляют — это и должно быть видно.
        assertNull(seen.header(AuthInterceptor.HEADER_AUTHORIZATION))
    }

    @Test
    fun `build without an inspector keeps the chain as it was`() {
        // Release: Chucker приезжает вариантом no-op, интерцептора нет.
        val client = NetworkFactory.mainClient(
            backendUrlInterceptor = addressInterceptor,
            authInterceptor = tokenInterceptor,
            authenticator = Authenticator.NONE,
            inspector = null,
        )

        assertEquals(listOf(addressInterceptor, tokenInterceptor), client.interceptors)
    }

    @Test
    fun `inspector is the last link of the chain`() {
        val inspector = RecordingInspector()

        val client = NetworkFactory.mainClient(
            backendUrlInterceptor = addressInterceptor,
            authInterceptor = tokenInterceptor,
            authenticator = Authenticator.NONE,
            inspector = inspector,
        )

        assertEquals(
            listOf(addressInterceptor, tokenInterceptor, inspector),
            client.interceptors,
        )
        assertTrue("логирование в тестовой конфигурации выключено", client.interceptors.size == 3)
    }

    @Test
    fun `both clients accept the certificate the user trusted`() {
        // issue #32: вход и запрос кода из SMS идут по «голому» refresh-клиенту,
        // остальное — по основному. Самоподписанный сертификат стенда обязан
        // проходить на обоих, иначе доверие лечит половину приложения.
        val stand = SelfSignedServer().apply { start() }
        stand.server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        stand.server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val pin = CertificatePinSource { stand.fingerprint }

        val main = NetworkFactory.mainClient(
            backendUrlInterceptor = addressInterceptor,
            authInterceptor = tokenInterceptor,
            authenticator = Authenticator.NONE,
            certificatePin = pin,
        )
        val refresh = NetworkFactory.refreshClient(
            backendUrlInterceptor = addressInterceptor,
            certificatePin = pin,
        )

        try {
            assertEquals(200, main.code(stand.url("/places").toString()))
            assertEquals(200, refresh.code(stand.url("/auth/otp/request").toString()))
        } finally {
            stand.shutdown()
        }
    }

    @Test
    fun `both clients reject the same certificate without the pin`() {
        val stand = SelfSignedServer().apply { start() }
        val pin = CertificatePinSource { null }

        val main = NetworkFactory.mainClient(
            backendUrlInterceptor = addressInterceptor,
            authInterceptor = tokenInterceptor,
            authenticator = Authenticator.NONE,
            certificatePin = pin,
        )
        val refresh = NetworkFactory.refreshClient(
            backendUrlInterceptor = addressInterceptor,
            certificatePin = pin,
        )

        try {
            val url = stand.url("/places").toString()
            assertTrue(
                runCatching { main.code(url) }.exceptionOrNull() is SSLException,
            )
            assertTrue(
                runCatching { refresh.code(url) }.exceptionOrNull() is SSLException,
            )
        } finally {
            stand.shutdown()
        }
    }

    private fun OkHttpClient.code(url: String): Int =
        newCall(Request.Builder().url(url).build()).execute().use { it.code }

    private class RecordingInspector : Interceptor {
        var request: Request? = null
            private set

        override fun intercept(chain: Interceptor.Chain): Response {
            request = chain.request()
            return chain.proceed(chain.request())
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val MARKER = "rewritten"

        fun Request.bodyAsText(): String = Buffer().also { body?.writeTo(it) }.readUtf8()
    }
}
