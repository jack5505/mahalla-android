package uz.mahalla.data.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Разбор введённого адреса и подстановка его в запрос (issue #26).
 *
 * Ошибка здесь ломает вообще все сетевые запросы, а видно её только на
 * устройстве, поэтому правила закреплены тестом целиком.
 */
class BackendUrlTest {

    @Test
    fun `host with port gets http scheme and trailing slash`() {
        // Пользователь набирает то, что видит в консоли бэкенда.
        assertEquals("http://192.168.0.10:8080/", BackendUrl.normalize("192.168.0.10:8080"))
    }

    @Test
    fun `https address is kept as is`() {
        assertEquals("https://api.mahalla.uz/", BackendUrl.normalize("https://api.mahalla.uz"))
    }

    @Test
    fun `path keeps a trailing slash for retrofit`() {
        // Без завершающего слэша Retrofit отбрасывает последний сегмент пути.
        assertEquals(
            "http://10.0.2.2:8080/api/v1/",
            BackendUrl.normalize("http://10.0.2.2:8080/api/v1"),
        )
    }

    @Test
    fun `surrounding spaces are trimmed`() {
        // Адрес чаще всего приходит вставкой из мессенджера.
        assertEquals("https://api.mahalla.uz/", BackendUrl.normalize("  https://api.mahalla.uz  "))
    }

    @Test
    fun `query and fragment are dropped`() {
        // baseUrl с параметрами Retrofit склеит с эндпоинтом в мусор.
        assertEquals(
            "https://api.mahalla.uz/api/",
            BackendUrl.normalize("https://api.mahalla.uz/api/?debug=1#top"),
        )
    }

    @Test
    fun `blank and broken input is rejected`() {
        assertNull(BackendUrl.normalize(""))
        assertNull(BackendUrl.normalize("   "))
        assertNull(BackendUrl.normalize("http://"))
        assertNull(BackendUrl.normalize("api mahalla uz"))
    }

    @Test
    fun `non http schemes are rejected`() {
        // Схему мы дописываем сами, но чужую не переписываем молча.
        assertNull(BackendUrl.normalize("ws://api.mahalla.uz"))
        assertNull(BackendUrl.normalize("ftp://api.mahalla.uz"))
    }

    @Test
    fun `rewrite moves the request to another host and path`() {
        val rewritten = BackendUrl.rewrite(
            requestUrl = "http://10.0.2.2:8080/api/v1/places/p-1".toHttpUrl(),
            templateBase = TEMPLATE,
            targetBase = "https://api.mahalla.uz/".toHttpUrl(),
        )

        // Путь baseUrl'а сборки срезан, путь нового адреса подставлен.
        assertEquals("https://api.mahalla.uz/places/p-1", rewritten.toString())
    }

    @Test
    fun `rewrite keeps the query`() {
        val rewritten = BackendUrl.rewrite(
            requestUrl = "http://10.0.2.2:8080/api/v1/places?page=2&query=osh".toHttpUrl(),
            templateBase = TEMPLATE,
            targetBase = "http://192.168.0.10:9090/backend/".toHttpUrl(),
        )

        assertEquals(
            "http://192.168.0.10:9090/backend/places?page=2&query=osh",
            rewritten.toString(),
        )
    }

    @Test
    fun `rewrite preserves escaping in path segments`() {
        val rewritten = BackendUrl.rewrite(
            requestUrl = "http://10.0.2.2:8080/api/v1/places/a%2Fb".toHttpUrl(),
            templateBase = TEMPLATE,
            targetBase = "http://host/".toHttpUrl(),
        )

        assertEquals("http://host/places/a%2Fb", rewritten.toString())
    }

    @Test
    fun `rewrite to the same address changes nothing`() {
        val request = "http://10.0.2.2:8080/api/v1/places/p-1".toHttpUrl()

        assertEquals(request, BackendUrl.rewrite(request, TEMPLATE, TEMPLATE))
    }

    @Test
    fun `a foreign path is kept when the template prefix does not match`() {
        // Абсолютный @Url в Retrofit: молча испортить такой путь нельзя.
        val rewritten = BackendUrl.rewrite(
            requestUrl = "http://cdn.example.com/photo/1.jpg".toHttpUrl(),
            templateBase = TEMPLATE,
            targetBase = "http://host:8080/".toHttpUrl(),
        )

        assertEquals("http://host:8080/photo/1.jpg", rewritten.toString())
    }

    private companion object {
        /** baseUrl отладочной сборки — он же шаблон пути. */
        val TEMPLATE = "http://10.0.2.2:8080/api/v1/".toHttpUrl()
    }
}
