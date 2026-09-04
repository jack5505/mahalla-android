package uz.mahalla.feature.media.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правила загрузки (issue #101): что берём у пользователя, во что должны
 * уложиться и как считаем размер после прореживания.
 *
 * Числа лимитов сняты со стенда curl'ом (см. [MediaUploadLimits]) — тест
 * закрепляет, что бюджет файла меньше лимита nginx, а не равен ему: разницу
 * съедает обвязка multipart, и промах здесь стоит `413` вместо загрузки.
 */
class MediaUploadTest {

    @Test
    fun `file budget leaves room for the multipart envelope`() {
        assertTrue(MediaUploadLimits.MAX_FILE_BYTES < MediaUploadLimits.MAX_REQUEST_BYTES)
        assertEquals(
            MediaUploadLimits.MAX_REQUEST_BYTES - MediaUploadLimits.MULTIPART_OVERHEAD_BYTES,
            MediaUploadLimits.MAX_FILE_BYTES,
        )
    }

    @Test
    fun `only images are accepted`() {
        assertTrue(MediaUploadLimits.isSupported("image/jpeg"))
        assertTrue(MediaUploadLimits.isSupported("image/png"))
        assertTrue(MediaUploadLimits.isSupported("IMAGE/HEIC"))
        // Тип от ContentResolver приезжает и с параметрами.
        assertTrue(MediaUploadLimits.isSupported("image/jpeg; charset=binary"))

        assertFalse(MediaUploadLimits.isSupported("application/pdf"))
        assertFalse(MediaUploadLimits.isSupported("video/mp4"))
        assertFalse(MediaUploadLimits.isSupported(null))
    }

    @Test
    fun `quality ladder goes down and never reaches mush`() {
        val ladder = MediaUploadLimits.JPEG_QUALITY_LADDER
        assertEquals(ladder.sortedDescending(), ladder)
        assertTrue(ladder.last() >= 40)
        assertTrue(ladder.first() <= 90)
    }

    @Test
    fun `rejection codes survive the round trip through ApiError`() {
        MediaRejection.entries.forEach { rejection ->
            assertEquals(rejection, MediaRejection.fromCode(rejection.code))
        }
        assertNull(MediaRejection.fromCode("SOMETHING_ELSE"))
        assertNull(MediaRejection.fromCode(null))
    }

    @Test
    fun `unknown media type does not hide the file`() {
        assertEquals(MediaType.Image, MediaType.fromApi("IMAGE"))
        assertEquals(MediaType.Video, MediaType.fromApi(" video "))
        assertEquals(MediaType.Document, MediaType.fromApi("DOCUMENT"))
        assertEquals(MediaType.Unknown, MediaType.fromApi("AUDIO"))
        assertEquals(MediaType.Unknown, MediaType.fromApi(null))
    }
}
