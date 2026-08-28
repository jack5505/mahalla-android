package uz.mahalla.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.BuildConfig

/**
 * Адрес бэкенда по умолчанию (issue #44).
 *
 * Это значение видит пользователь: экран ввода адреса подставляет его в поле,
 * пока свой адрес не задан, и на него же возвращает кнопка «по умолчанию»
 * (`BackendUrlStore.buildDefault`). Опечатка в нём означает приложение, которое
 * при первом открытии никуда не ходит, а заметно это только на устройстве.
 */
class BackendDefaultUrlTest {

    @Test
    fun `build default is already normalized`() {
        // Иначе кнопка «по умолчанию» подставляла бы одну строку, а сохранялась
        // бы другая — и адрес в поле не совпадал бы с тем, куда уходят запросы.
        assertEquals(BuildConfig.API_BASE_URL, BackendUrl.normalize(BuildConfig.API_BASE_URL))
    }

    @Test
    fun `build default keeps the api prefix of the backend contract`() {
        // Эндпоинты объявлены относительно baseUrl (`auth/send-otp`, issue #42),
        // а на бэкенде живут под `/api/v1/` — префикс входит в baseUrl.
        assertTrue(BuildConfig.API_BASE_URL, BuildConfig.API_BASE_URL.endsWith("/api/v1/"))
    }

    @Test
    fun `debug build points at the development stand`() {
        // Стенд отдаёт https с сертификатом Let's Encrypt (домен nip.io
        // резолвится в 189.74.96.232), поэтому доверять сертификату руками
        // (issue #32) больше не требуется.
        if (!BuildConfig.DEBUG) return
        assertEquals("https://189-74-96-232.nip.io/api/v1/", BuildConfig.API_BASE_URL)
        assertTrue(BuildConfig.API_BASE_URL, BuildConfig.API_BASE_URL.startsWith("https://"))
    }
}
