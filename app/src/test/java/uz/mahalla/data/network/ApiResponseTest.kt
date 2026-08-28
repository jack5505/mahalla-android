package uz.mahalla.data.network

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import uz.mahalla.core.result.ApiEnvelopeException

/**
 * Конверт ответа бэкенда (issue #42): полезная нагрузка в `data`, причина
 * отказа в `error`.
 */
class ApiResponseTest {

    private val json = NetworkFactory.json()

    @Test
    fun `payload is unwrapped from a successful envelope`() {
        val response = decode("""{"success":true,"data":"ok","timestamp":"2026-08-28T18:00:00Z"}""")

        assertEquals("ok", response.payload())
    }

    @Test
    fun `failed envelope carries the code and the message`() {
        val response = decode(
            """{"success":false,"error":{"code":"VALIDATION_ERROR",
               "message":"Joylashuv ruxsatini yoqing"}}""",
        )

        val thrown = assertThrows(ApiEnvelopeException::class.java) { response.payload() }
        assertEquals("VALIDATION_ERROR", thrown.code)
        assertEquals("Joylashuv ruxsatini yoqing", thrown.serverMessage)
    }

    @Test
    fun `success without data is a failure too`() {
        // Вызывающему нечего показать: «успех» с пустым результатом — это
        // пустой экран без объяснения.
        val response = decode("""{"success":true}""")

        assertThrows(ApiEnvelopeException::class.java) { response.payload() }
    }

    @Test
    fun `envelope message is used when the error block is missing`() {
        val response = decode("""{"success":false,"message":"Xizmat vaqtincha ishlamayapti"}""")

        val thrown = assertThrows(ApiEnvelopeException::class.java) { response.payload() }
        assertEquals("Xizmat vaqtincha ishlamayapti", thrown.serverMessage)
    }

    @Test
    fun `unknown envelope fields do not break parsing`() {
        // Бэкенд развивается быстрее клиента: новое поле не повод падать.
        val response = decode("""{"success":true,"data":"ok","traceId":"abc","extra":{"a":1}}""")

        assertEquals("ok", response.payload())
    }

    private fun decode(body: String): ApiResponse<String> =
        json.decodeFromString(ApiResponse.serializer(String.serializer()), body)
}
