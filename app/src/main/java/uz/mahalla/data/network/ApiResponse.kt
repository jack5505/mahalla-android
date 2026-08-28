package uz.mahalla.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uz.mahalla.core.result.ApiEnvelopeException

/**
 * Конверт ответа бэкенда Mahalla: полезная нагрузка лежит в `data`, причина
 * отказа — в `error`.
 *
 * ```json
 * {"success":false,"error":{"code":"VALIDATION_ERROR","message":"Joylashuv ruxsatini yoqing"},
 *  "timestamp":"2026-08-28T18:23:03Z"}
 * ```
 *
 * Все поля необязательные: конверт один на весь API, а какие именно поля
 * приедут, зависит от эндпоинта и от исхода запроса.
 */
@Serializable
data class ApiResponse<T>(
    @SerialName("success") val success: Boolean = true,
    @SerialName("message") val message: String? = null,
    @SerialName("data") val data: T? = null,
    @SerialName("error") val error: ApiErrorBody? = null,
    @SerialName("timestamp") val timestamp: String? = null,
)

@Serializable
data class ApiErrorBody(
    /** Машинный код: `VALIDATION_ERROR`, `OTP_EXPIRED`, `TOKEN_INVALID`. */
    @SerialName("code") val code: String? = null,
    /** Текст для человека — на языке, который выбрал бэкенд. */
    @SerialName("message") val message: String? = null,
)

/**
 * Полезная нагрузка или исключение.
 *
 * Отказ бэкенда почти всегда приезжает с HTTP-кодом 4xx/5xx, и тогда его
 * разбирает `apiCall` из `HttpException`. Этот путь — про ответ 2xx с
 * `success: false` (и про 2xx без `data`, что для вызывающего то же самое:
 * данных нет). Без него `data == null` пришлось бы разбирать на каждом
 * вызове, а «успех без результата» тихо превращался бы в пустой экран.
 */
fun <T : Any> ApiResponse<T>.payload(): T {
    val payload = data
    if (!success || payload == null) {
        throw ApiEnvelopeException(
            code = error?.code,
            serverMessage = error?.message ?: message,
        )
    }
    return payload
}
