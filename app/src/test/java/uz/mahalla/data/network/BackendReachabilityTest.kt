package uz.mahalla.data.network

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверка адреса перед сохранением (issue #26).
 *
 * Смысл проверки — «сервер вообще отвечает», а не «отвечает успехом»:
 * корневой путь бэкенда обычно отдаёт 404 или требует авторизации.
 */
class BackendReachabilityTest {

    private val reachability = OkHttpBackendReachability()

    @Test
    fun `answering server is reachable`() = runTest {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200))

        assertTrue(reachability.check(server.url("/").toString()))

        // Тело не запрашиваем: нужен сам факт ответа.
        assertEquals("HEAD", server.takeRequest().method)
        server.shutdown()
    }

    @Test
    fun `not found still means the server is there`() = runTest {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(404))

        assertTrue(reachability.check(server.url("/").toString()))
        server.shutdown()
    }

    @Test
    fun `nobody listening on the port is not reachable`() = runTest {
        val server = MockWebServer()
        server.start()
        val url = server.url("/").toString()
        server.shutdown()

        assertFalse(reachability.check(url))
    }

    @Test
    fun `garbage is not reachable`() = runTest {
        assertFalse(reachability.check("не адрес"))
    }
}
