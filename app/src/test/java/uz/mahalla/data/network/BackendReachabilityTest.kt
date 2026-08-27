package uz.mahalla.data.network

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.data.network.tls.CertificatePinSource
import uz.mahalla.testutil.SelfSignedServer

/**
 * Проверка адреса перед сохранением (issue #26, #32).
 *
 * Смысл проверки — «сервер вообще отвечает», а не «отвечает успехом»:
 * корневой путь бэкенда обычно отдаёт 404 или требует авторизации. Отдельно
 * важно, что провал TLS не выдаётся за молчание сервера (issue #32).
 */
class BackendReachabilityTest {

    private var pin: String? = null
    private val reachability = OkHttpBackendReachability(CertificatePinSource { pin })

    @Test
    fun `answering server is reachable`() = runTest {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200))

        assertEquals(BackendCheck.Reachable, reachability.check(server.url("/").toString()))

        // Тело не запрашиваем: нужен сам факт ответа.
        assertEquals("HEAD", server.takeRequest().method)
        server.shutdown()
    }

    @Test
    fun `not found still means the server is there`() = runTest {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(404))

        assertEquals(BackendCheck.Reachable, reachability.check(server.url("/").toString()))
        server.shutdown()
    }

    @Test
    fun `nobody listening on the port is not reachable`() = runTest {
        val server = MockWebServer()
        server.start()
        val url = server.url("/").toString()
        server.shutdown()

        assertEquals(BackendCheck.Unreachable, reachability.check(url))
    }

    @Test
    fun `garbage is not reachable`() = runTest {
        assertEquals(BackendCheck.Unreachable, reachability.check("не адрес"))
    }

    @Test
    fun `an untrusted certificate is not silence`() = runTest {
        // Случай из issue #32: сервер на месте, а handshake не проходит.
        // Раньше это доезжало до экрана как «сервер не ответил».
        val stand = SelfSignedServer().apply { start() }
        stand.server.enqueue(MockResponse().setResponseCode(200))

        val result = reachability.check(stand.url("/").toString())

        val untrusted = result as? BackendCheck.UntrustedCertificate
        assertTrue("ожидался разбор сертификата, был $result", untrusted != null)
        assertEquals(stand.fingerprint, untrusted!!.certificate.sha256)
        assertTrue(untrusted.certificate.subject.contains("mahalla-stand"))
        stand.shutdown()
    }

    @Test
    fun `a certificate for another name is reported too`() = runTest {
        // Сервер на голом IP: сертификат вообще без подходящего имени.
        val stand = SelfSignedServer(subjectAlternativeName = null).apply { start() }
        stand.server.enqueue(MockResponse().setResponseCode(200))

        val result = reachability.check(stand.url("/").toString())

        assertEquals(
            stand.fingerprint,
            (result as BackendCheck.UntrustedCertificate).certificate.sha256,
        )
        stand.shutdown()
    }

    @Test
    fun `a trusted certificate makes the server reachable`() = runTest {
        // После подтверждения доверия проверка обязана проходить — иначе экран
        // показывал бы ошибку на сервере, с которым приложение уже говорит.
        val stand = SelfSignedServer().apply { start() }
        stand.server.enqueue(MockResponse().setResponseCode(401))
        pin = stand.fingerprint

        assertEquals(BackendCheck.Reachable, reachability.check(stand.url("/").toString()))
        stand.shutdown()
    }

    @Test
    fun `a plain http server that went away is not blamed on TLS`() = runTest {
        // http-адрес за сертификатом не ходит: второй запрос — потерянные
        // секунды ожидания пользователя.
        val server = MockWebServer()
        server.start()
        val url = server.url("/").toString()
        server.shutdown()

        assertEquals(BackendCheck.Unreachable, reachability.check(url))
    }
}
