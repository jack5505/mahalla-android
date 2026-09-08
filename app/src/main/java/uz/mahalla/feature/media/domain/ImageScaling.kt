package uz.mahalla.feature.media.domain

/** Размер картинки в пикселях. */
data class ImageSize(val width: Int, val height: Int)

/**
 * Арифметика сжатия — чистыми функциями, отдельно от `BitmapFactory`
 * (issue #101).
 *
 * Вынесено из [uz.mahalla.feature.media.data.AndroidImageCompressor]
 * намеренно: сам декодер на JVM не поднять (нужен Robolectric и настоящий
 * JPEG), а ошибка именно здесь — в выборе кратности прореживания или в угле
 * поворота — стоит либо лишних мегабайт в запросе, либо аватара боком.
 */
object ImageScaling {

    /**
     * `inSampleSize` для `BitmapFactory`: степень двойки, при которой
     * прореженная картинка **ещё не меньше** нужной. Меньше брать нельзя —
     * увеличивать обратно значит терять резкость на ровном месте.
     *
     * Ноль и отрицательные размеры (декодер отвечает `-1` на файл, который не
     * разобрал) дают `1`: решение о таком файле принимает вызывающий, а не
     * эта функция.
     */
    fun sampleSize(width: Int, height: Int, maxDimension: Int): Int {
        if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= maxDimension || height / (sample * 2) >= maxDimension) {
            sample *= 2
        }
        return sample
    }

    /**
     * Размер после подгонки под длинную сторону, пропорции сохраняются.
     * Картинку меньше лимита не трогаем: растягивать её незачем.
     *
     * Короткая сторона не может стать нулём — иначе `createScaledBitmap`
     * упадёт на панораме 4000×1.
     */
    fun targetSize(width: Int, height: Int, maxDimension: Int): ImageSize {
        if (width <= 0 || height <= 0 || maxDimension <= 0) return ImageSize(width, height)
        val longest = maxOf(width, height)
        if (longest <= maxDimension) return ImageSize(width, height)
        val scale = maxDimension.toDouble() / longest
        return ImageSize(
            width = (width * scale).toInt().coerceAtLeast(1),
            height = (height * scale).toInt().coerceAtLeast(1),
        )
    }

    /**
     * Угол поворота из тега EXIF. Снимок с камеры почти всегда лежит в файле
     * боком, а ориентацию описывает тегом: без этого аватар оказывается
     * повёрнутым, и человек решит, что сломалось приложение.
     *
     * Значения — константы EXIF (`ORIENTATION_ROTATE_90` и соседние). Свои, а
     * не из `android.media.ExifInterface`: они нужны и здесь, где Android нет.
     * Зеркальные варианты (2, 4, 5, 7) приводятся к ближайшему повороту —
     * отражение по горизонтали телефоны ставят только фронтальной камере, и
     * терять его не жалко, а поворот терять нельзя.
     */
    fun rotationDegrees(exifOrientation: Int): Int = when (exifOrientation) {
        ORIENTATION_ROTATE_90, ORIENTATION_TRANSPOSE -> DEGREES_90
        ORIENTATION_ROTATE_180, ORIENTATION_FLIP_VERTICAL -> DEGREES_180
        ORIENTATION_ROTATE_270, ORIENTATION_TRANSVERSE -> DEGREES_270
        else -> 0
    }

    private const val ORIENTATION_FLIP_VERTICAL = 4
    private const val ORIENTATION_TRANSPOSE = 5
    private const val ORIENTATION_ROTATE_90 = 6
    private const val ORIENTATION_TRANSVERSE = 7
    private const val ORIENTATION_ROTATE_270 = 8
    private const val ORIENTATION_ROTATE_180 = 3

    private const val DEGREES_90 = 90
    private const val DEGREES_180 = 180
    private const val DEGREES_270 = 270
}
