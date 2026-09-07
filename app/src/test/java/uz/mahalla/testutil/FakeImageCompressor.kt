package uz.mahalla.testutil

import uz.mahalla.feature.media.data.CompressedImage
import uz.mahalla.feature.media.data.CompressionResult
import uz.mahalla.feature.media.data.ImageCompressor
import uz.mahalla.feature.media.domain.MediaRejection

/**
 * Сжатие в тестах (issue #101): `BitmapFactory` на JVM нет, а проверять надо
 * то, что уходит в сеть.
 *
 * @param bytes содержимое «снимка». По умолчанию читаемый ASCII — чтобы
 * multipart-тело можно было сверить строкой.
 */
class FakeImageCompressor(
    var bytes: ByteArray = DEFAULT_BYTES.toByteArray(),
    var mimeType: String = "image/jpeg",
    var fileName: String = "photo.jpg",
    var rejection: MediaRejection? = null,
) : ImageCompressor {

    /** С каким адресом позвали в последний раз. */
    var lastSource: String? = null
        private set

    override suspend fun compress(source: String): CompressionResult {
        lastSource = source
        return rejection?.let(CompressionResult::Rejected)
            ?: CompressionResult.Success(CompressedImage(bytes, mimeType, fileName))
    }

    companion object {
        const val DEFAULT_BYTES = "mahalla-photo-bytes"
    }
}
