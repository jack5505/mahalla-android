package uz.mahalla.data.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Что не должно попасть в logcat в debug-сборке (issue #102).
 *
 * `HttpLoggingInterceptor` умеет прятать заголовки, но не тело, а PIN, код из
 * SMS и токены приложение шлёт и получает именно телом. PIN здесь тот же, что
 * открывает кошелёк.
 *
 * Проверяется **поведение собранного клиента**, а не список путей: тест на
 * одну лишь [NetworkFactory.carriesSecretBody] остался бы зелёным, если
 * выкинуть выбор уровня из `loggingInterceptor()` и вернуть `Level.BODY` на
 * всё — то есть ровно при том регрессе, ради которого правка и делалась.
 */
class SecretBodyLoggingTest {

    private val server = MockWebServer()
    private val lines = mutableListOf<String>()

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `pin never reaches the log`() {
        val body = """{"currentPin":"111111","newPin":"222222","deviceId":"d-1"}"""

        val logged = exchange(path = "/api/v1/pin/change", requestBody = body, response = """{"ok":true}""")

        assertFalse("PIN уехал в logcat: $logged", logged.contains("111111"))
        assertFalse("Новый PIN уехал в logcat: $logged", logged.contains("222222"))
        // Строка запроса и код ответа остаются — без них отладка сети слепая.
        assertTrue(logged.contains("/api/v1/pin/change"))
    }

    /**
     * Ответ печатается так же, как запрос: `telegram/check` кладёт токены в
     * корень ответа (issue #46), и на `Level.BODY` refresh-токен ушёл бы в
     * logcat целиком.
     */
    @Test
    fun `tokens in a response body never reach the log`() {
        val response = """{"accessToken":"AT-secret","refreshToken":"RT-secret"}"""

        val logged = exchange(path = "/api/v1/auth/telegram/check", requestBody = "{}", response = response)

        assertFalse("access-токен уехал в logcat: $logged", logged.contains("AT-secret"))
        assertFalse("refresh-токен уехал в logcat: $logged", logged.contains("RT-secret"))
    }

    /**
     * `PayoutResponse` возвращает тот же номер карты, что принял запрос
     * (issue #290) — это PAN, а не PIN, но критерий списка тот же: «тем можно
     * заплатить».
     */
    @Test
    fun `card number in a payout never reaches the log`() {
        val body = """{"amount":100000,"cardNumber":"4400123456789012"}"""
        val response = """{"id":"p-1","cardNumber":"4400123456789012","status":"PENDING"}"""

        val logged = exchange(path = "/api/v1/wallet/business/payouts", requestBody = body, response = response)

        assertFalse("номер карты уехал в logcat: $logged", logged.contains("4400123456789012"))
        assertTrue(logged.contains("/api/v1/wallet/business/payouts"))
    }

    @Test
    fun `ordinary endpoints keep their body in the log`() {
        val response = """{"places":["bozor"]}"""

        val logged = exchange(path = "/api/v1/places", requestBody = """{"query":"bozor"}""", response = response)

        // Инспектор трафика нужен ровно ради тел: гасить их у всех ручек
        // значило бы выключить отладку сети целиком.
        assertTrue("Обычная ручка перестала логировать тело: $logged", logged.contains("bozor"))
    }

    @Test
    fun `a trailing slash does not smuggle the body into the log`() {
        assertTrue(NetworkFactory.carriesSecretBody("/api/v1/pin/change/"))
        assertTrue(NetworkFactory.carriesSecretBody("/api/v1/auth/telegram/check"))
        assertTrue(NetworkFactory.carriesSecretBody("/api/v1/auth/send-otp"))
        assertFalse(NetworkFactory.carriesSecretBody("/api/v1/pin/status"))
    }

    /** Один запрос через настоящий клиент; возвращает всё, что тот напечатал. */
    private fun exchange(path: String, requestBody: String, response: String): String {
        server.enqueue(MockResponse().setResponseCode(200).setBody(response))
        val client = NetworkFactory.clientBuilder(
            logBodies = true,
            logger = { message -> lines += message },
        ).build()

        val request = Request.Builder()
            .url(server.url(path))
            .post(requestBody.toRequestBody(NetworkFactory.CONTENT_TYPE.toMediaType()))
            .build()
        client.newCall(request).execute().use { it.body?.string() }

        return lines.joinToString("\n")
    }
}
