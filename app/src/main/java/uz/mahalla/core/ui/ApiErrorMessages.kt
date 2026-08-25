package uz.mahalla.core.ui

import androidx.annotation.StringRes
import uz.mahalla.R
import uz.mahalla.core.result.ApiError

/**
 * Единственное место, где ошибка сетевого слоя превращается в текст (эпик
 * 1.3 + 1.5). Сообщения — только из ресурсов, чтобы работали оба языка.
 */
@StringRes
fun ApiError.messageRes(): Int = when (this) {
    ApiError.NoConnection -> R.string.error_no_connection
    ApiError.Timeout -> R.string.error_timeout
    ApiError.Unauthorized -> R.string.error_unauthorized
    ApiError.Forbidden -> R.string.error_forbidden
    ApiError.NotFound -> R.string.error_not_found
    ApiError.Serialization -> R.string.error_unknown
    is ApiError.Http -> if (code >= SERVER_ERROR_CODE) {
        R.string.error_server
    } else {
        R.string.error_unknown
    }
    is ApiError.Unexpected -> R.string.error_unknown
}

private const val SERVER_ERROR_CODE = 500
