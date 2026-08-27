package uz.mahalla.core.result

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException

class ApiResultTest {

    @Test
    fun `success wraps the value`() = runTest {
        assertEquals(ApiResult.Success("ok"), apiCall { "ok" })
    }

    @Test
    fun `timeout is distinguished from a generic io error`() = runTest {
        assertEquals(
            ApiResult.Failure(ApiError.Timeout),
            apiCall { throw SocketTimeoutException() },
        )
        assertEquals(
            ApiResult.Failure(ApiError.NoConnection),
            apiCall { throw IOException("dns") },
        )
    }

    @Test
    fun `http codes are mapped to domain errors`() = runTest {
        assertEquals(ApiError.Unauthorized, apiCall { throw httpException(401) }.errorOrNull())
        assertEquals(ApiError.Forbidden, apiCall { throw httpException(403) }.errorOrNull())
        assertEquals(ApiError.NotFound, apiCall { throw httpException(404) }.errorOrNull())

        val serverFailure = apiCall { throw httpException(500) }
        val error = (serverFailure as ApiResult.Failure).error
        assertTrue(error is ApiError.Http && error.code == 500)
    }

    @Test
    fun `the body of an http error reaches the caller`() = runTest {
        // Ровно случай из issue #34: классификация говорит «нет доступа», а
        // причину знает только бэкенд.
        val body = """
            {"success":false,"error":{"code":"GEO_PERMISSION_REQUIRED",
            "message":"Joylashuv ruxsatini yoqing"}}
        """.trimIndent()

        val failure = apiCall { throw httpException(403, body) } as ApiResult.Failure

        assertEquals(ApiError.Forbidden, failure.error)
        assertEquals("GEO_PERMISSION_REQUIRED", failure.failure.server?.code)
        assertEquals("Joylashuv ruxsatini yoqing", failure.failure.serverMessage)
    }

    @Test
    fun `failures without a response carry no server payload`() = runTest {
        // Показывать «HTTP 0» и пустое тело — врать: ответа не было вовсе.
        assertNull(apiCall { throw IOException("dns") }.failureOrNull()?.server)
        assertNull(apiCall { throw SocketTimeoutException() }.failureOrNull()?.server)
    }

    @Test
    fun `broken payload is a serialization error`() = runTest {
        assertEquals(
            ApiResult.Failure(ApiError.Serialization),
            apiCall { throw SerializationException("unexpected token") },
        )
    }

    @Test
    fun `unknown failures are not swallowed as network ones`() = runTest {
        val result = apiCall { throw IllegalStateException("bug") }
        val error = (result as ApiResult.Failure).error
        assertTrue(error is ApiError.Unexpected)
    }

    @Test
    fun `cancellation is rethrown, not turned into a failure`() {
        // Проглотить отмену — значит сломать структурную конкурентность:
        // корутина «успешно» завершится уже после отмены скоупа.
        var rethrown = false
        try {
            runBlocking { apiCall<Unit> { throw CancellationException("cancelled") } }
        } catch (expected: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
    }

    @Test
    fun `map transforms success and passes failure through`() = runTest {
        assertEquals(ApiResult.Success(2), ApiResult.Success(1).map { it + 1 })

        val failure: ApiResult<Int> = ApiResult.Failure(ApiError.Timeout)
        assertEquals(failure, failure.map { it + 1 })
    }

    @Test
    fun `accessors expose only the matching side`() {
        val success: ApiResult<String> = ApiResult.Success("ok")
        assertEquals("ok", success.dataOrNull())
        assertNull(success.errorOrNull())

        val failure: ApiResult<String> = ApiResult.Failure(ApiError.NoConnection)
        assertNull(failure.dataOrNull())
        assertEquals(ApiError.NoConnection, failure.errorOrNull())
    }

    private fun httpException(code: Int, body: String = ""): HttpException = HttpException(
        Response.error<Unit>(code, body.toResponseBody("application/json".toMediaType())),
    )
}
