package uz.mahalla.feature.media.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import uz.mahalla.core.crash.CrashReporting
import uz.mahalla.core.crash.reportSwallowed
import uz.mahalla.core.result.runCatchingCancellable
import uz.mahalla.feature.media.domain.ImageScaling
import uz.mahalla.feature.media.domain.MediaRejection
import uz.mahalla.feature.media.domain.MediaUploadLimits
import java.io.ByteArrayOutputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Сжатие снимка перед отправкой (issue #101).
 *
 * Порядок шагов не косметика:
 *
 * 1. **тип файла** — по `ContentResolver`, до чтения байтов: pdf и видео
 *    отклоняются, не занимая ни памяти, ни сети;
 * 2. **границы** (`inJustDecodeBounds`) — узнать размер, не поднимая в память
 *    12 мегапикселей;
 * 3. **прореживание** (`inSampleSize`) — единственный способ декодировать
 *    большой снимок, не рискуя `OutOfMemoryError`;
 * 4. **поворот по EXIF** — снимок с камеры лежит в файле боком;
 * 5. **точный масштаб** под длинную сторону;
 * 6. **качество JPEG сверху вниз**, пока не влезет в лимит nginx.
 *
 * Всё это на [Dispatchers.Default]: декодирование и `Bitmap.compress` — это
 * счёт, а не ожидание, и на главном потоке они дают заметный фриз.
 *
 * Отказы наружу уходят значениями [MediaRejection], а не исключениями: у
 * каждого свой текст на экране, и «не удалось» без причины человеку ничего не
 * объясняет.
 */
@Singleton
class AndroidImageCompressor @Inject constructor(
    @ApplicationContext private val context: Context,
) : ImageCompressor {

    override suspend fun compress(source: String): CompressionResult =
        withContext(Dispatchers.Default) { compressHere(source) }

    private suspend fun compressHere(source: String): CompressionResult {
        val uri = runCatchingCancellable { Uri.parse(source) }.getOrNull()
            ?: return rejected(MediaRejection.Unreadable)
        val resolver = context.contentResolver

        val declaredType = runCatchingCancellable { resolver.getType(uri) }.getOrNull()
        if (declaredType != null && !MediaUploadLimits.isSupported(declaredType)) {
            return rejected(MediaRejection.UnsupportedType)
        }

        return try {
            encode(resolver, uri, declaredType)
        } catch (memory: OutOfMemoryError) {
            // Снимок не поместился в память даже прореженным. Для человека это
            // ровно «слишком большой файл», и другого действия, кроме «выберите
            // другой», у него нет. В отчёты уходит вручную:
            // `runCatchingCancellable` ловит только `Exception`, а нехватка
            // памяти на снимке говорит о железе пользователя больше, чем любой
            // лог.
            CrashReporting.recordNonFatal(memory, "media.compress")
            rejected(MediaRejection.TooLarge)
        }
    }

    private suspend fun encode(
        resolver: ContentResolver,
        uri: Uri,
        declaredType: String?,
    ): CompressionResult {
        val bounds = runCatchingCancellable { decodeBounds(resolver, uri) }
            .reportSwallowed("media.decodeBounds")
            .getOrNull()
            ?: return rejected(MediaRejection.Unreadable)

        // Тип не сказал ни `ContentResolver`, ни сам файл — верить нечему.
        if (declaredType == null && !MediaUploadLimits.isSupported(bounds.outMimeType)) {
            return rejected(MediaRejection.UnsupportedType)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return rejected(MediaRejection.Unreadable)
        }

        coroutineContext.ensureActive()
        val sample = ImageScaling.sampleSize(
            width = bounds.outWidth,
            height = bounds.outHeight,
            maxDimension = MediaUploadLimits.MAX_IMAGE_DIMENSION,
        )
        val decoded = runCatchingCancellable { decodeBitmap(resolver, uri, sample) }
            .reportSwallowed("media.decodeBitmap")
            .getOrNull()
            ?: return rejected(MediaRejection.Unreadable)

        coroutineContext.ensureActive()
        val rotation = runCatchingCancellable { rotationOf(resolver, uri) }.getOrNull() ?: 0
        val prepared = decoded.rotated(rotation).scaledToLimit()

        val bytes = compressToLimit(prepared)
        prepared.recycle()
        return if (bytes == null) {
            rejected(MediaRejection.TooLarge)
        } else {
            CompressionResult.Success(
                CompressedImage(
                    bytes = bytes,
                    mimeType = MediaUploadLimits.OUTPUT_MIME_TYPE,
                    fileName = fileNameOf(resolver, uri),
                ),
            )
        }
    }

    private fun decodeBounds(resolver: ContentResolver, uri: Uri): BitmapFactory.Options? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: return null
        return options
    }

    private fun decodeBitmap(resolver: ContentResolver, uri: Uri, sampleSize: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
    }

    private fun rotationOf(resolver: ContentResolver, uri: Uri): Int {
        val orientation = resolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
        return ImageScaling.rotationDegrees(orientation)
    }

    private fun Bitmap.rotated(degrees: Int): Bitmap {
        if (degrees == 0) return this
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
        if (rotated !== this) recycle()
        return rotated
    }

    private fun Bitmap.scaledToLimit(): Bitmap {
        val target = ImageScaling.targetSize(
            width = width,
            height = height,
            maxDimension = MediaUploadLimits.MAX_IMAGE_DIMENSION,
        )
        if (target.width == width && target.height == height) return this
        val scaled = Bitmap.createScaledBitmap(this, target.width, target.height, true)
        if (scaled !== this) recycle()
        return scaled
    }

    /**
     * Качество перебирается сверху вниз: первое, что уложилось в бюджет, и
     * уходит на сервер. `null` — не уложилось ни на одном, и тогда честнее
     * отказать, чем отправить заведомый `413`.
     */
    private suspend fun compressToLimit(bitmap: Bitmap): ByteArray? {
        MediaUploadLimits.JPEG_QUALITY_LADDER.forEach { quality ->
            coroutineContext.ensureActive()
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            val bytes = stream.toByteArray()
            if (bytes.size <= MediaUploadLimits.MAX_FILE_BYTES) return bytes
        }
        return null
    }

    /**
     * Имя файла для сервера (`originalName` в ответе). Расширение всегда
     * `.jpg`: что бы человек ни выбрал, уезжает JPEG, и `photo.heic` с
     * JPEG-байтами внутри — верный способ получить битую картинку в галерее
     * заведения.
     */
    private fun fileNameOf(resolver: ContentResolver, uri: Uri): String {
        val displayName = runCatchingCancellable {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()
        val base = displayName
            ?.substringBeforeLast('.')
            ?.replace(UNSAFE_NAME, "_")
            ?.take(MAX_NAME_LENGTH)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_NAME
        return base.lowercase(Locale.ROOT) + ".jpg"
    }

    private fun rejected(reason: MediaRejection): CompressionResult =
        CompressionResult.Rejected(reason)

    private companion object {
        val UNSAFE_NAME = Regex("[^A-Za-z0-9._-]")
        const val MAX_NAME_LENGTH = 64
        const val DEFAULT_NAME = "photo"
    }
}
