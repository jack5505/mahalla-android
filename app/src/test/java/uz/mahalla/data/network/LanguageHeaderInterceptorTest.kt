package uz.mahalla.data.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * `Accept-Language` в каждом запросе (issue #242).
 *
 * Пробой стенда (`189-74-96-232.nip.io`, 2026-09-14) подтвердил: `users/me`
 * без заголовка отвечает `401` на uz, с `Accept-Language: ru` — на ru. Значит
 * заголовка достаточно, писать `language` в `UpdateMeRequest` не нужно.
 */
class LanguageHeaderInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var defaultLocale: Locale

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        defaultLocale = Locale.getDefault()
    }

    @After
    fun tearDown() {
        server.shutdown()
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun `system locale ru becomes the ru tag`() {
        Locale.setDefault(Locale.forLanguageTag("ru-RU"))

        assertEquals("ru", call().getHeader(LanguageHeaderInterceptor.HEADER_ACCEPT_LANGUAGE))
    }

    @Test
    fun `system locale uz becomes the uz tag`() {
        Locale.setDefault(Locale.forLanguageTag("uz-Latn-UZ"))

        assertEquals("uz", call().getHeader(LanguageHeaderInterceptor.HEADER_ACCEPT_LANGUAGE))
    }

    @Test
    fun `an unsupported system locale falls back to uz`() {
        // Бэкенд знает только UZ и RU (docs/adr/0007) — третьего варианта нет,
        // а пустой заголовок хуже неверного: молча уйти без языка нельзя.
        Locale.setDefault(Locale.forLanguageTag("en-US"))

        assertEquals("uz", call().getHeader(LanguageHeaderInterceptor.HEADER_ACCEPT_LANGUAGE))
    }

    @Test
    fun `an explicit header is not overwritten`() {
        Locale.setDefault(Locale.forLanguageTag("ru-RU"))

        val request = call(
            Request.Builder()
                .url(server.url("/users/me"))
                .header(LanguageHeaderInterceptor.HEADER_ACCEPT_LANGUAGE, "uz"),
        )

        assertEquals("uz", request.getHeader(LanguageHeaderInterceptor.HEADER_ACCEPT_LANGUAGE))
    }

    private val client by lazy {
        OkHttpClient.Builder().addInterceptor(LanguageHeaderInterceptor()).build()
    }

    private fun call(
        builder: Request.Builder = Request.Builder().url(server.url("/users/me")),
    ): RecordedRequest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        client.newCall(builder.build()).execute().close()
        return server.takeRequest()
    }
}
