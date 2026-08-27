package uz.mahalla.core.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор тела ошибки (issue #34). Схема ответа у каждого звена своя, поэтому
 * тесты фиксируют не «правильный» формат, а то, что текст находится в любом из
 * встречающихся, а неизвестное тело не теряется и не притворяется сообщением.
 */
class ServerErrorParserTest {

    @Test
    fun `envelope of the mahalla backend is understood`() {
        val server = ServerErrorParser.parse(
            httpCode = 403,
            httpMessage = "Forbidden",
            requestLine = "POST https://api.mahalla.uz/auth/otp/request",
            body = """
                {"success":false,"error":{"code":"GEO_PERMISSION_REQUIRED",
                "message":"Joylashuv ruxsatini yoqing"},"timestamp":"2026-08-27T16:38:13Z"}
            """.trimIndent(),
        )

        assertEquals(403, server.httpCode)
        assertEquals("Forbidden", server.httpMessage)
        assertEquals("GEO_PERMISSION_REQUIRED", server.code)
        assertEquals("Joylashuv ruxsatini yoqing", server.message)
        assertEquals("POST https://api.mahalla.uz/auth/otp/request", server.requestLine)
        assertTrue(server.hasMessage)
    }

    @Test
    fun `escaped newlines of the server text survive`() {
        // Бэкенд присылает инструкцию в несколько строк — в JSON это \n, и
        // человек должен увидеть переносы, а не escape-последовательности.
        val server = ServerErrorParser.parse(
            httpCode = 403,
            body = """{"error":{"message":"Yoqing:\nSozlamalar → Ruxsatlar"}}""",
        )

        assertEquals("Yoqing:\nSozlamalar → Ruxsatlar", server.message)
    }

    @Test
    fun `flat message and error string are both read`() {
        assertEquals(
            "Token is expired",
            ServerErrorParser.parse(httpCode = 401, body = """{"message":"Token is expired"}""")
                .message,
        )
        // Spring Security и OAuth-совместимые ответы: текст лежит в error.
        assertEquals(
            "invalid_grant",
            ServerErrorParser.parse(httpCode = 400, body = """{"error":"invalid_grant"}""").message,
        )
        assertEquals(
            "Bad credentials",
            ServerErrorParser.parse(
                httpCode = 401,
                body = """{"error":"unauthorized","error_description":"Bad credentials"}""",
            ).message,
        )
    }

    @Test
    fun `json is kept for display with indentation`() {
        val server = ServerErrorParser.parse(
            httpCode = 500,
            body = """{"error":{"code":"INTERNAL"}}""",
        )

        assertTrue("тело показывается человеку — оно должно читаться", server.body!!.contains("\n"))
        assertTrue(server.body!!.contains("INTERNAL"))
    }

    @Test
    fun `html page from a proxy is not shown as a message`() {
        val html = "<html><head><title>502 Bad Gateway</title></head><body>nginx</body></html>"

        val server = ServerErrorParser.parse(httpCode = 502, body = html)

        assertNull("страницу nginx вместо текста ошибки показывать нельзя", server.message)
        assertEquals("но в подробностях она остаётся", html, server.body)
    }

    @Test
    fun `short plain text is a message, a stack trace is not`() {
        assertEquals(
            "Service temporarily unavailable",
            ServerErrorParser.parse(httpCode = 503, body = "Service temporarily unavailable")
                .message,
        )

        val trace = "java.lang.NullPointerException\n\tat uz.mahalla.Api.handle(Api.kt:42)"
        assertNull(ServerErrorParser.parse(httpCode = 500, body = trace).message)
    }

    @Test
    fun `a json string literal loses its quotes`() {
        // `ResponseEntity<String>` у Spring отдаёт валидный JSON — строку в
        // кавычках. Показывать кавычки пользователю незачем.
        val server = ServerErrorParser.parse(httpCode = 503, body = "\"Service unavailable\"")

        assertEquals("Service unavailable", server.message)
        assertEquals("Service unavailable", server.body)
    }

    @Test
    fun `payload of the envelope is not mistaken for the error text`() {
        // В конверте Mahalla `data` — полезная нагрузка: её заголовок не имеет
        // отношения к причине отказа.
        val server = ServerErrorParser.parse(
            httpCode = 409,
            body = """{"success":false,"data":{"title":"Bosh sahifa"}}""",
        )

        assertNull(server.message)
    }

    @Test
    fun `empty and broken bodies do not break the parser`() {
        val empty = ServerErrorParser.parse(httpCode = 404, body = "   ")
        assertNull(empty.message)
        assertNull(empty.body)
        assertEquals(404, empty.httpCode)

        val broken = ServerErrorParser.parse(httpCode = 500, body = "{\"error\":")
        assertNull("обрывок JSON — не сообщение", broken.message)
        assertEquals("{\"error\":", broken.body)

        val nulls = ServerErrorParser.parse(httpCode = 500, body = """{"message":null,"code":null}""")
        assertNull(nulls.message)
        assertNull(nulls.code)
    }

    @Test
    fun `a huge body is truncated`() {
        val huge = "x".repeat(5_000)

        val body = ServerErrorParser.parse(httpCode = 500, body = huge).body!!

        assertTrue("состояние экрана не место для мегабайтного ответа", body.length < 2_100)
        assertTrue(body.endsWith("…"))
    }
}
