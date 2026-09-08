package uz.mahalla.feature.media.data

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

/**
 * Тело запроса, которое рассказывает, сколько байт уже ушло (issue #101).
 *
 * Нужно потому, что загрузка — единственное действие в приложении, где
 * ожидание зависит от связи, а не от сервера: на медленном интернете
 * неподвижная крутилка неотличима от зависшего экрана.
 *
 * Байты пишутся кусками и после каждого куска [BufferedSink.flush] выталкивает
 * их в сокет. Без этого прогресс врал бы: OkHttp сложил бы весь файл в свой
 * буфер за один вызов и отчитался о 100 % раньше, чем ушёл первый пакет.
 *
 * [writeTo] может быть вызван **не один раз** — например когда
 * `TokenAuthenticator` повторяет запрос после обновления токена. Тогда счёт
 * начинается заново с нуля, и это правильнее, чем продолжить с прошлого
 * места: заново уходит весь файл.
 */
internal class ProgressRequestBody(
    private val bytes: ByteArray,
    private val contentType: MediaType?,
    private val onProgress: (bytesWritten: Long, total: Long) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long = bytes.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        val total = bytes.size.toLong()
        var written = 0L
        onProgress(0, total)
        while (written < total) {
            val chunk = minOf(CHUNK_BYTES.toLong(), total - written).toInt()
            sink.write(bytes, written.toInt(), chunk)
            sink.flush()
            written += chunk
            onProgress(written, total)
        }
    }

    private companion object {
        /** Сегмент okio: писать мельче смысла нет, крупнее — грубее прогресс. */
        const val CHUNK_BYTES = 8 * 1024
    }
}
