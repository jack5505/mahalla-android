package uz.mahalla.feature.media.domain

import java.util.Locale

/**
 * Файл, который бэкенд принял и сохранил (issue #101).
 *
 * Схема `MediaFile` в `/v3/api-docs` встречается один раз — коллизии
 * springdoc, из-за которой поля запросов приходилось выводить из ответов
 * (issue #76, #84, #97), здесь нет.
 *
 * @param id идентификатор на сервере. Может оказаться пустым: удаление
 * (`DELETE media/{id}`) появится вместе с экраном, который умеет показывать
 * загруженное (#60), а до тех пор отсутствие id — не повод считать удачную
 * загрузку неудачной.
 * @param url адрес файла. **Единственное обязательное поле**: ради него
 * загрузка и делается, и ответ без него — отказ, а не «успех без результата».
 */
data class MediaFile(
    val id: String,
    val url: String,
    val thumbnailUrl: String? = null,
    val type: MediaType = MediaType.Image,
    val sizeBytes: Long = 0,
    val originalName: String? = null,
)

/** `MediaFile.type` бэкенда. Незнакомое значение файл не прячет. */
enum class MediaType {
    Image,
    Video,
    Document,
    Unknown,
    ;

    companion object {
        fun fromApi(raw: String?): MediaType = when (raw?.trim()?.uppercase(Locale.ROOT)) {
            "IMAGE" -> Image
            "VIDEO" -> Video
            "DOCUMENT" -> Document
            else -> Unknown
        }
    }
}
