package uz.mahalla.core.result

/**
 * Бэкенд ответил 2xx, но в конверте сказал `success: false` (либо не положил
 * `data`). HTTP-кода отказа при этом нет, поэтому `apiCall` не смог бы понять
 * из ответа ничего — исключение переносит код и текст ошибки к нему.
 *
 * Наследник `RuntimeException`, а не `IOException`: `apiCall` считает
 * `IOException` обрывом связи, а связь здесь как раз была.
 */
class ApiEnvelopeException(
    val code: String?,
    val serverMessage: String?,
) : RuntimeException(serverMessage ?: code ?: "backend reported failure")
