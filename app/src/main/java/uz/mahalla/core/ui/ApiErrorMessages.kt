package uz.mahalla.core.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import uz.mahalla.R
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure

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
    // Отказ по конверту: причину назвал сам бэкенд, и она уже показана
    // (см. userMessage). Своего текста для машинного кода у нас нет.
    is ApiError.Business -> R.string.error_unknown
}

/**
 * Текст ошибки для пользователя (issue #34): сообщение бэкенда, если оно есть,
 * иначе общий текст по классификации.
 *
 * Сервер знает причину точнее клиента: «включите доступ к геолокации» вместо
 * «нет прав на это действие». Свои строки остаются фоллбэком — на случай
 * пустого тела, HTML от прокси и отсутствия сети.
 */
@Composable
fun ApiFailure.userMessage(): String = serverMessage ?: stringResource(error.messageRes())

private const val SERVER_ERROR_CODE = 500
