package uz.mahalla.feature.media.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import uz.mahalla.R
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.userMessage
import uz.mahalla.feature.media.domain.MediaRejection

@StringRes
fun MediaRejection.messageRes(): Int = when (this) {
    MediaRejection.Unreadable -> R.string.media_error_unreadable
    MediaRejection.UnsupportedType -> R.string.media_error_unsupported
    MediaRejection.TooLarge -> R.string.media_error_too_large
}

/**
 * Текст отказа загрузки (issue #101).
 *
 * Отказы бывают двух разных родов, и путать их нельзя. Сетевые объясняет сам
 * бэкенд (issue #34) — его текст точнее нашего. А отказ клиента (файл не
 * открылся, не картинка, не влезает даже сжатым) сервер объяснить не может:
 * он о такой попытке не знает вовсе, и `ApiError.Business` с нашим кодом
 * пришёл бы на экран общим «Nimadir xato ketdi».
 */
@Composable
fun ApiFailure.mediaMessage(): String {
    val rejection = (error as? ApiError.Business)?.let { MediaRejection.fromCode(it.code) }
    return if (rejection != null) stringResource(rejection.messageRes()) else userMessage()
}
