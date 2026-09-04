package uz.mahalla.core.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Вычистка секретов (issue #74). Критерий готовности T8 — «в отчёте нет
 * токенов», и проверяется он здесь: дальше по коду строка уже уходит в сеть.
 */
class SecretScrubberTest {

    private val accessToken =
        "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIrOTk4OTAxMTEyMjMzIn0.7cQ1mM_Yk3sTn2pQwErTyUiOpAsDfGh"

    @Test
    fun `bearer token is cut out of a message`() {
        val scrubbed = SecretScrubber.scrubText("HTTP 401 with Authorization: Bearer $accessToken")

        assertFalse(scrubbed!!.contains(accessToken))
        assertTrue(scrubbed.contains("Bearer ${SecretScrubber.REDACTED}"))
        // Остальное сообщение остаётся: без него отчёт бесполезен.
        assertTrue(scrubbed.contains("HTTP 401"))
    }

    @Test
    fun `bare jwt is cut out of a message`() {
        val scrubbed = SecretScrubber.scrubText("refresh failed for $accessToken")

        assertEquals("refresh failed for ${SecretScrubber.REDACTED}", scrubbed)
    }

    @Test
    fun `named secrets are cut out whatever the separator`() {
        val cases = mapOf(
            "otpToken=abc123def" to "otpToken=",
            "\"pin\":\"0000\"" to "\"pin\":",
            "refresh_token: qwerty12345" to "refresh_token: ",
            "password = hunter2" to "password = ",
        )

        cases.forEach { (input, prefix) ->
            val scrubbed = SecretScrubber.scrubText(input)!!
            assertTrue(input, scrubbed.startsWith(prefix + SecretScrubber.REDACTED))
        }
    }

    @Test
    fun `text without secrets is left alone`() {
        val text = "Failed to open /data/user/0/uz.mahalla/files/datastore/settings.preferences_pb"

        assertEquals(text, SecretScrubber.scrubText(text))
    }

    @Test
    fun `null and empty stay as they are`() {
        assertNull(SecretScrubber.scrubText(null))
        assertEquals("", SecretScrubber.scrubText(""))
        assertNull(SecretScrubber.scrubUrl(null))
        assertNull(SecretScrubber.scrubQuery(null))
        assertNull(SecretScrubber.scrubMap(null))
    }

    @Test
    fun `only secret query parameters are cut, the rest identify the request`() {
        val url = "https://189-74-96-232.nip.io/api/v1/auth/telegram/check" +
            "?deepLinkToken=abc123&device=ANDROID&page=2"

        val scrubbed = SecretScrubber.scrubUrl(url)!!

        assertFalse(scrubbed.contains("abc123"))
        assertTrue(scrubbed.contains("deepLinkToken=${SecretScrubber.REDACTED}"))
        assertTrue(scrubbed.contains("device=ANDROID"))
        assertTrue(scrubbed.contains("page=2"))
        assertTrue(scrubbed.startsWith("https://189-74-96-232.nip.io/api/v1/auth/telegram/check?"))
    }

    @Test
    fun `url without query is left alone`() {
        val url = "https://189-74-96-232.nip.io/api/v1/places/nearby"

        assertEquals(url, SecretScrubber.scrubUrl(url))
    }

    @Test
    fun `secret headers are cut, the rest are readable`() {
        val headers = mapOf(
            "Authorization" to "Bearer $accessToken",
            "X-Session-Id" to "8f14e45f-ea9d-4fc4-8e2b-1a3c5d7e9f01",
            "Cookie" to "JSESSIONID=42",
            "X-Geo-Lat" to "41.311100",
            "Content-Type" to "application/json",
        )

        val scrubbed = SecretScrubber.scrubMap(headers)!!

        assertEquals(SecretScrubber.REDACTED, scrubbed["Authorization"])
        assertEquals(SecretScrubber.REDACTED, scrubbed["X-Session-Id"])
        assertEquals(SecretScrubber.REDACTED, scrubbed["Cookie"])
        assertEquals("41.311100", scrubbed["X-Geo-Lat"])
        assertEquals("application/json", scrubbed["Content-Type"])
    }

    @Test
    fun `header name is matched regardless of case`() {
        val scrubbed = SecretScrubber.scrubMap(mapOf("authorization" to "Bearer $accessToken"))!!

        assertEquals(SecretScrubber.REDACTED, scrubbed["authorization"])
    }

    @Test
    fun `token inside a value of a non-secret field is cut too`() {
        val scrubbed = SecretScrubber.scrubMap(mapOf("note" to "sent with Bearer $accessToken"))!!

        assertFalse(scrubbed.getValue("note").contains(accessToken))
    }

    @Test
    fun `secret names cover the fields the app actually stores`() {
        listOf(
            "accessToken",
            "refreshToken",
            "otpToken",
            "deepLinkToken",
            "PinHash",
            "X-Session-Id",
            "sentry.dsn",
        ).forEach { name -> assertTrue(name, SecretScrubber.isSecretName(name)) }

        listOf("placeId", "page", "X-Geo-Lat", "Content-Type").forEach { name ->
            assertFalse(name, SecretScrubber.isSecretName(name))
        }
    }
}
