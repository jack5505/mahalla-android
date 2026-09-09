package uz.mahalla.feature.media.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Арифметика сжатия (issue #101). Проверяется на JVM ровно потому, что сам
 * декодер сюда не приезжает: ошибка в кратности прореживания видна только как
 * «фото почему-то не грузится», а ошибка в угле поворота — как аватар боком.
 */
class ImageScalingTest {

    @Test
    fun `sample size keeps the picture at least as large as the limit`() {
        // 4000×3000 при лимите 1600: /2 = 2000 (ещё больше), /4 = 1000 (уже
        // меньше) — значит 2.
        assertEquals(2, ImageScaling.sampleSize(4000, 3000, 1600))
        assertEquals(4, ImageScaling.sampleSize(8000, 6000, 1600))
        // Меньше лимита не прореживаем вовсе.
        assertEquals(1, ImageScaling.sampleSize(1200, 900, 1600))
    }

    @Test
    fun `sample size never drops below one on a broken file`() {
        // `BitmapFactory` отвечает -1 на файл, который не разобрал.
        assertEquals(1, ImageScaling.sampleSize(-1, -1, 1600))
        assertEquals(1, ImageScaling.sampleSize(0, 0, 1600))
        assertEquals(1, ImageScaling.sampleSize(4000, 3000, 0))
    }

    @Test
    fun `target size fits the longest side and keeps proportions`() {
        val landscape = ImageScaling.targetSize(4000, 3000, 1600)
        assertEquals(1600, landscape.width)
        assertEquals(1200, landscape.height)

        val portrait = ImageScaling.targetSize(3000, 4000, 1600)
        assertEquals(1200, portrait.width)
        assertEquals(1600, portrait.height)
    }

    @Test
    fun `small picture is left alone`() {
        assertEquals(ImageSize(800, 600), ImageScaling.targetSize(800, 600, 1600))
        assertEquals(ImageSize(1600, 100), ImageScaling.targetSize(1600, 100, 1600))
    }

    @Test
    fun `panorama keeps at least one pixel on the short side`() {
        val size = ImageScaling.targetSize(8000, 1, 1600)
        assertEquals(1600, size.width)
        assertTrue(size.height >= 1)
    }

    @Test
    fun `exif orientation becomes a rotation`() {
        assertEquals(0, ImageScaling.rotationDegrees(1))
        assertEquals(90, ImageScaling.rotationDegrees(6))
        assertEquals(180, ImageScaling.rotationDegrees(3))
        assertEquals(270, ImageScaling.rotationDegrees(8))
        // Зеркальные варианты приводятся к ближайшему повороту: отражение
        // теряется, поворот — нет.
        assertEquals(90, ImageScaling.rotationDegrees(5))
        assertEquals(270, ImageScaling.rotationDegrees(7))
        // Тега нет или он мусорный — снимок не крутим.
        assertEquals(0, ImageScaling.rotationDegrees(0))
        assertEquals(0, ImageScaling.rotationDegrees(42))
    }
}
