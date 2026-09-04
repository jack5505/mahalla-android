package uz.mahalla.feature.media.data

import uz.mahalla.feature.media.domain.MediaRejection

/**
 * Картинка, готовая к отправке: уже сжатая и уже уложенная в лимит
 * (issue #101).
 *
 * Байты держатся в памяти, а не во временном файле: после сжатия это сотни
 * килобайт, зато отменённая загрузка не оставляет мусора в хранилище, а
 * повтор не зависит от того, жив ли ещё файл.
 */
class CompressedImage(
    val bytes: ByteArray,
    val mimeType: String,
    val fileName: String,
) {
    val sizeBytes: Long get() = bytes.size.toLong()
}

sealed interface CompressionResult {
    data class Success(val image: CompressedImage) : CompressionResult

    /** Причина, по которой файл не поедет на сервер — см. [MediaRejection]. */
    data class Rejected(val reason: MediaRejection) : CompressionResult
}

/**
 * Сжатие выбранного снимка.
 *
 * За интерфейсом — ради тестов: репозиторий проверяется на MockWebServer без
 * `BitmapFactory`, которого на JVM нет. Настоящая реализация —
 * [AndroidImageCompressor].
 */
interface ImageCompressor {

    /** @param source `content://`-адрес, который вернул photo picker. */
    suspend fun compress(source: String): CompressionResult
}
