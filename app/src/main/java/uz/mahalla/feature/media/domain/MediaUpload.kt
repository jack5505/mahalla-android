package uz.mahalla.feature.media.domain

import java.util.Locale

/**
 * Ограничения загрузки (issue #101). В схеме бэкенда их нет вовсе, поэтому
 * сняты руками со стенда 2026-09-04:
 *
 * ```
 * 1000 KiB тела → 401 UNAUTHORIZED (то есть запрос дошёл до приложения)
 * 1024 KiB тела → 413 Request Entity Too Large, HTML от nginx
 * ```
 *
 * То есть режет **не** бэкенд, а nginx перед ним: `client_max_body_size` со
 * значением по умолчанию — 1 МиБ. Отсюда два следствия, определяющие всю
 * задачу:
 *
 * 1. **Сжатие обязательно.** Снимок с камеры в 12 Мп — это 3–5 МБ, и без
 *    сжатия каждая загрузка кончалась бы страницей nginx на английском, из
 *    которой человеку не понять ничего.
 * 2. **Проверять размер надо до отправки.** 413 приходит от прокси, без
 *    конверта `{success,error}`, то есть текста для человека в нём нет
 *    (issue #34 показал бы голый HTML).
 *
 * Лимит nginx почти наверняка не задуман — про это заведено замечание бэкенду
 * (см. отчёт issue #101). Когда его поднимут, здесь меняется одно число.
 */
object MediaUploadLimits {

    /** `client_max_body_size` стенда: всё тело запроса целиком. */
    const val MAX_REQUEST_BYTES: Long = 1L * 1024 * 1024

    /**
     * Запас на обвязку multipart: границы частей, заголовок `Content-Disposition`
     * с именем файла и заголовки самого запроса. Считать её точно незачем —
     * это десятки байт, а промах здесь стоит 413 вместо загрузки.
     */
    const val MULTIPART_OVERHEAD_BYTES: Long = 24L * 1024

    /** Столько остаётся самому файлу. */
    const val MAX_FILE_BYTES: Long = MAX_REQUEST_BYTES - MULTIPART_OVERHEAD_BYTES

    /**
     * Длинная сторона снимка после сжатия. 1600 px хватает и на аватар, и на
     * фото витрины во весь экран телефона; дальше растёт только вес.
     */
    const val MAX_IMAGE_DIMENSION: Int = 1600

    /**
     * Качество JPEG перебирается сверху вниз, пока файл не уложится в бюджет.
     * Ниже 45 портится уже заметно — такой снимок лучше отклонить и сказать
     * об этом словами, чем молча загрузить кашу.
     */
    val JPEG_QUALITY_LADDER: List<Int> = listOf(85, 75, 65, 55, 45)

    /** Что берём у пользователя. Всё прочее отклоняется до чтения файла. */
    val ALLOWED_MIME_TYPES: Set<String> = setOf(
        "image/jpeg",
        "image/jpg",
        "image/png",
        "image/webp",
        "image/heic",
        "image/heif",
    )

    /** Что уходит на сервер: сжимаем всегда в JPEG. */
    const val OUTPUT_MIME_TYPE: String = "image/jpeg"

    fun isSupported(mimeType: String?): Boolean =
        mimeType?.trim()?.lowercase(Locale.ROOT)?.substringBefore(';') in ALLOWED_MIME_TYPES
}

/**
 * Почему файл не ушёл на сервер. Это отказы **клиента**: до сети такой файл не
 * доезжает, и объяснять их должен клиент — сервер об этой попытке не знает.
 *
 * [code] уезжает в `ApiError.Business`, как код отказа незаполненной формы в
 * issue #84: экран показывает по нему свой текст, а сетевые отказы
 * по-прежнему показываются текстом сервера (issue #34).
 */
enum class MediaRejection(val code: String) {

    /** Файл не открылся или не разобрался как картинка. */
    Unreadable("MEDIA_UNREADABLE"),

    /** Не картинка вовсе: pdf, видео, архив. */
    UnsupportedType("MEDIA_UNSUPPORTED_TYPE"),

    /** Не влезает в [MediaUploadLimits.MAX_FILE_BYTES] даже после сжатия. */
    TooLarge("MEDIA_TOO_LARGE"),
    ;

    companion object {
        fun fromCode(code: String?): MediaRejection? =
            entries.firstOrNull { it.code == code }
    }
}
