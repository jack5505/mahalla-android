package uz.mahalla.core.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Приведение ссылок на картинки к загружаемому виду (issue #60). */
class ImageUrlTest {

    private val base = "https://189-74-96-232.nip.io/api/v1/"

    @Test
    fun `absolute https url is kept as is`() {
        assertEquals(
            "https://cdn.example.com/a.jpg",
            ImageUrl.resolve(base, "https://cdn.example.com/a.jpg"),
        )
    }

    @Test
    fun `absolute http url is kept as is`() {
        assertEquals("http://10.0.2.2:8080/a.jpg", ImageUrl.resolve(base, "http://10.0.2.2:8080/a.jpg"))
    }

    @Test
    fun `root relative path takes host of the backend, not its path`() {
        assertEquals(
            "https://189-74-96-232.nip.io/media/entity/42.jpg",
            ImageUrl.resolve(base, "/media/entity/42.jpg"),
        )
    }

    @Test
    fun `relative path is resolved against api path`() {
        assertEquals(
            "https://189-74-96-232.nip.io/api/v1/media/42.jpg",
            ImageUrl.resolve(base, "media/42.jpg"),
        )
    }

    @Test
    fun `query is kept`() {
        assertEquals(
            "https://189-74-96-232.nip.io/api/v1/media/42.jpg?w=200",
            ImageUrl.resolve(base, "media/42.jpg?w=200"),
        )
    }

    @Test
    fun `protocol relative url gets https`() {
        assertEquals("https://cdn.example.com/a.jpg", ImageUrl.resolve(base, "//cdn.example.com/a.jpg"))
    }

    @Test
    fun `blank and null are nothing to load`() {
        assertNull(ImageUrl.resolve(base, null))
        assertNull(ImageUrl.resolve(base, ""))
        assertNull(ImageUrl.resolve(base, "   "))
    }

    @Test
    fun `whitespace around url is trimmed`() {
        assertEquals(
            "https://cdn.example.com/a.jpg",
            ImageUrl.resolve(base, "  https://cdn.example.com/a.jpg  "),
        )
    }

    @Test
    fun `data uri is allowed`() {
        val data = "data:image/png;base64,iVBORw0KGgo="
        assertEquals(data, ImageUrl.resolve(base, data))
    }

    /**
     * Строку присылает сервер, а адрес сервера в debug задаёт пользователь:
     * ссылка в локальное хранилище не должна доехать до загрузчика.
     */
    @Test
    fun `local schemes are rejected`() {
        assertNull(ImageUrl.resolve(base, "file:///sdcard/DCIM/photo.jpg"))
        assertNull(ImageUrl.resolve(base, "content://com.android.providers/1"))
        assertNull(ImageUrl.resolve(base, "android.resource://uz.mahalla/drawable/ic"))
    }

    @Test
    fun `broken url does not blow up`() {
        assertNull(ImageUrl.resolve(base, "http://exa mple.com/a.jpg"))
    }

    @Test
    fun `relative url without a usable backend address is nothing to load`() {
        assertNull(ImageUrl.resolve("", "media/42.jpg"))
        assertNull(ImageUrl.resolve("not an url", "media/42.jpg"))
    }

    /** Адрес без завершающего слэша: последний сегмент — не каталог. */
    @Test
    fun `base without trailing slash resolves like a file path`() {
        assertEquals(
            "https://host.example/api/media.jpg",
            ImageUrl.resolve("https://host.example/api/v1", "media.jpg"),
        )
    }
}
