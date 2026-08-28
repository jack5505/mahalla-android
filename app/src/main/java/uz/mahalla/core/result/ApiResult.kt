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

    /**
     * Отказ. Хранится [ApiFailure] целиком — вместе с ответом сервера, который
     * потом показывается пользователю (issue #34); [error] остаётся для логики,
     * которой нужна только классификация.
     *
     * **Сравнивать по значению можно только [error], а не `Failure` целиком.**
     * `equals` учитывает и [ApiFailure.server], а тело ответа парсер заполняет
     * всегда: `assertEquals(ApiResult.Failure(ApiError.Forbidden), result)`
     * ложно для любого настоящего HTTP-отказа, потому что справа приедет ещё и
     * [ServerError]. В логике по той же причине ветвиться нужно по
     * `result.error` (`when`, `==`), а не по варианту `Failure`.
     */
    data class Failure(val failure: ApiFailure) : ApiResult<Nothing> {
        constructor(error: ApiError) : this(ApiFailure(error))

        val error: ApiError get() = failure.error
    }
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(data))
    is ApiResult.Failure -> this
}

fun <T> ApiResult<T>.dataOrNull(): T? = (this as? ApiResult.Success)?.data

fun <T> ApiResult<T>.errorOrNull(): ApiError? = (this as? ApiResult.Failure)?.error

fun <T> ApiResult<T>.failureOrNull(): ApiFailure? = (this as? ApiResult.Failure)?.failure

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
    } catch (envelope: ApiEnvelopeException) {
        // Ответ 2xx с `success: false`: HTTP-кода отказа нет, причина — только
        // в теле. Код ответа сохраняем настоящий (200), чтобы подробности не
        // врали про то, чего сервер не говорил.
        ApiResult.Failure(
            ApiFailure(
                error = ApiError.Business(envelope.code),
                server = ServerError(
                    httpCode = HTTP_OK,
                    code = envelope.code,
                    message = envelope.serverMessage,
                ),
            ),
        )
    } catch (http: HttpException) {
        // Тело ответа разбирается здесь, потому что дальше его уже никто не
        // увидит: HttpException до UI не доезжает (issue #34).
        ApiResult.Failure(
            ApiFailure(
                error = ApiError.fromHttpCode(http.code(), http.message()),
                server = ServerErrorParser.parse(http),
            ),
        )
    } catch (serialization: SerializationException) {
        ApiResult.Failure(ApiError.Serialization)
    } catch (io: IOException) {
        ApiResult.Failure(ApiError.NoConnection)
    } catch (other: Throwable) {
        ApiResult.Failure(ApiError.Unexpected(other))
    }

private const val HTTP_OK = 200
