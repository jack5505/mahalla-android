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
        assertEquals(ApiResult.Failure(ApiError.Unauthorized), apiCall { throw httpException(401) })
        assertEquals(ApiResult.Failure(ApiError.Forbidden), apiCall { throw httpException(403) })
        assertEquals(ApiResult.Failure(ApiError.NotFound), apiCall { throw httpException(404) })

        val serverFailure = apiCall { throw httpException(500) }
        val error = (serverFailure as ApiResult.Failure).error
        assertTrue(error is ApiError.Http && error.code == 500)
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

    private fun httpException(code: Int): HttpException = HttpException(
        Response.error<Unit>(code, "".toResponseBody("application/json".toMediaType())),
    )
}
