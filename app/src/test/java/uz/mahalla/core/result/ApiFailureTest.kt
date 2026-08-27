package uz.mahalla.core.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Правило показа текста ошибки (issue #34): сообщение сервера вытесняет общее
 * только тогда, когда оно действительно есть.
 */
class ApiFailureTest {

    @Test
    fun `a blank server message is not a message`() {
        // Иначе на экране появилась бы пустая красная строка вместо
        // «нет доступа»: хуже, чем общий текст.
        val failure = ApiFailure(
            error = ApiError.Forbidden,
            server = ServerError(httpCode = 403, message = "   "),
        )

        assertNull(failure.serverMessage)
    }

    @Test
    fun `a real message wins over the classification`() {
        val failure = ApiFailure(
            error = ApiError.Forbidden,
            server = ServerError(httpCode = 403, message = "Joylashuv ruxsatini yoqing"),
        )

        assertEquals("Joylashuv ruxsatini yoqing", failure.serverMessage)
    }

    @Test
    fun `without a response there is nothing to show but the classification`() {
        val failure = ApiFailure(ApiError.NoConnection)

        assertNull(failure.serverMessage)
        assertNull(failure.server)
        assertEquals(ApiError.NoConnection, failure.error)
    }
}
