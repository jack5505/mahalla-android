package uz.mahalla.core.result

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Результат вызова API (эпик 1.3): либо данные, либо доменная [ApiError].
 * Исключения не выходят за пределы data-слоя.
 */
sealed interface ApiResult<out T> {
    data class Success<out T>(val data: T) : ApiResult<T>
    data class Failure(val error: ApiError) : ApiResult<Nothing>
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(data))
    is ApiResult.Failure -> this
}

fun <T> ApiResult<T>.dataOrNull(): T? = (this as? ApiResult.Success)?.data

fun <T> ApiResult<T>.errorOrNull(): ApiError? = (this as? ApiResult.Failure)?.error

/**
 * Обёртка над suspend-вызовом Retrofit: маппит исключения в [ApiError].
 *
 * Порядок catch важен: [SocketTimeoutException] — наследник [IOException],
 * а отмена корутины пробрасывается наружу, иначе ломается структурная
 * конкурентность.
 */
suspend fun <T> apiCall(block: suspend () -> T): ApiResult<T> =
    try {
        ApiResult.Success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (timeout: SocketTimeoutException) {
        ApiResult.Failure(ApiError.Timeout)
    } catch (http: HttpException) {
        ApiResult.Failure(ApiError.fromHttpCode(http.code(), http.message()))
    } catch (serialization: SerializationException) {
        ApiResult.Failure(ApiError.Serialization)
    } catch (io: IOException) {
        ApiResult.Failure(ApiError.NoConnection)
    } catch (other: Throwable) {
        ApiResult.Failure(ApiError.Unexpected(other))
    }
