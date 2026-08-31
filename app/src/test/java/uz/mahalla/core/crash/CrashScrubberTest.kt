package uz.mahalla.core.crash

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.protocol.Message
import io.sentry.protocol.Request
import io.sentry.protocol.SentryException
import io.sentry.protocol.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Последний шаг перед отправкой (issue #74): проверяется на настоящих объектах
 * Sentry, а не на своей копии моделей — иначе тест закреплял бы вычистку
 * полей, которых в отчёте нет, и пропускал те, что есть.
 */
class CrashScrubberTest {

    private val accessToken =
        "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIrOTk4OTAxMTEyMjMzIn0.7cQ1mM_Yk3sTn2pQwErTyUiOpAsDfGh"

    @Test
    fun `token is cut out of the message`() {
        val event = SentryEvent().apply {
            message = Message().apply {
                message = "login failed with Bearer $accessToken"
                formatted = "login failed with Bearer $accessToken"
                params = listOf("Bearer $accessToken")
            }
        }

        val scrubbed = CrashScrubber.scrub(event)

        assertFalse(scrubbed.message!!.message!!.contains(accessToken))
        assertFalse(scrubbed.message!!.formatted!!.contains(accessToken))
        assertFalse(scrubbed.message!!.params!!.single().contains(accessToken))
    }

    @Test
    fun `token is cut out of the exception text`() {
        val event = SentryEvent().apply {
            exceptions = listOf(
                SentryException().apply {
                    type = "IllegalStateException"
                    value = "refreshToken=qwerty12345 rejected"
                },
            )
        }

        val scrubbed = CrashScrubber.scrub(event)

        val text = scrubbed.exceptions!!.single().value!!
        assertFalse(text.contains("qwerty12345"))
        // Тип исключения остаётся: по нему отчёт и группируется.
        assertEquals("IllegalStateException", scrubbed.exceptions!!.single().type)
    }

    @Test
    fun `request loses its secrets but stays recognisable`() {
        val event = SentryEvent().apply {
            request = Request().apply {
                url = "https://189-74-96-232.nip.io/api/v1/auth/verify-otp?otpToken=abc123"
                method = "POST"
                queryString = "otpToken=abc123&device=ANDROID"
                headers = mapOf(
                    "Authorization" to "Bearer $accessToken",
                    "X-Session-Id" to "8f14e45f",
                    "Content-Type" to "application/json",
                )
                cookies = "JSESSIONID=42"
                data = """{"otpCode":"123456"}"""
            }
        }

        val scrubbed = CrashScrubber.scrub(event).request!!

        assertFalse(scrubbed.url!!.contains("abc123"))
        assertFalse(scrubbed.queryString!!.contains("abc123"))
        assertTrue(scrubbed.queryString!!.contains("device=ANDROID"))
        assertEquals(SecretScrubber.REDACTED, scrubbed.headers!!["Authorization"])
        assertEquals(SecretScrubber.REDACTED, scrubbed.headers!!["X-Session-Id"])
        assertEquals("application/json", scrubbed.headers!!["Content-Type"])
        // Тело не отправляется вовсе: по issue #34 бэкенд кладёт туда
        // произвольный текст, и что там окажется завтра — неизвестно.
        assertNull(scrubbed.data)
        assertNull(scrubbed.cookies)
        // Метод и путь остаются — без них отчёт не отвечает на вопрос «что упало».
        assertEquals("POST", scrubbed.method)
        assertTrue(scrubbed.url!!.contains("/api/v1/auth/verify-otp"))
    }

    @Test
    fun `secret extras and tags are cut`() {
        val event = SentryEvent().apply {
            setExtra("otpToken", "abc123")
            setExtra("attempt", 3)
            setExtra("note", "sent with Bearer $accessToken")
            setTag("operation", "pin.save")
            setTag("sessionToken", "abc123")
        }

        val scrubbed = CrashScrubber.scrub(event)

        assertEquals(SecretScrubber.REDACTED, scrubbed.getExtra("otpToken"))
        // Числа не трогаем: секрета в них нет, а тип поля ломать нельзя.
        assertEquals(3, scrubbed.getExtra("attempt"))
        assertFalse((scrubbed.getExtra("note") as String).contains(accessToken))
        assertEquals("pin.save", scrubbed.getTag("operation"))
        assertEquals(SecretScrubber.REDACTED, scrubbed.getTag("sessionToken"))
    }

    @Test
    fun `user ip address is dropped`() {
        val event = SentryEvent().apply {
            user = User().apply { ipAddress = "84.54.72.11" }
        }

        assertNull(CrashScrubber.scrub(event).user!!.ipAddress)
    }

    @Test
    fun `breadcrumbs attached to the event are scrubbed too`() {
        val event = SentryEvent().apply {
            addBreadcrumb(
                Breadcrumb().apply {
                    message = "POST /auth/verify-otp with Bearer $accessToken"
                    setData("url", "https://host/api/v1/auth/verify-otp?otpToken=abc123")
                    setData("status_code", 401)
                },
            )
        }

        val crumb = CrashScrubber.scrub(event).breadcrumbs!!.single()

        assertFalse(crumb.message!!.contains(accessToken))
        assertFalse((crumb.getData("url") as String).contains("abc123"))
        assertEquals(401, crumb.getData("status_code"))
    }

    @Test
    fun `breadcrumb with a secret key loses the value, not the key`() {
        val crumb = Breadcrumb().apply {
            setData("Authorization", "Bearer $accessToken")
            setData("path", "/api/v1/places/nearby")
        }

        val scrubbed = CrashScrubber.scrub(crumb)

        assertEquals(SecretScrubber.REDACTED, scrubbed.getData("Authorization"))
        assertEquals("/api/v1/places/nearby", scrubbed.getData("path"))
    }

    @Test
    fun `an event without secrets survives untouched`() {
        val event = SentryEvent().apply {
            exceptions = listOf(
                SentryException().apply {
                    type = "KeyStoreException"
                    value = "Keystore operation failed"
                },
            )
            setTag("operation", "pin.save")
        }

        val scrubbed = CrashScrubber.scrub(event)

        assertEquals("Keystore operation failed", scrubbed.exceptions!!.single().value)
        assertEquals("pin.save", scrubbed.getTag("operation"))
    }
}
