package uz.mahalla.data.network.tls

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.testutil.SelfSignedServer
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLException
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * Доверие к сертификату по отпечатку (issue #32).
 *
 * Сервер настоящий и сертификат настоящий — иначе непонятно, ловит ли пин тот
 * самый `SSLHandshakeException`, из-за которого приложение не могло достучаться
 * до `https://189.74.96.232/`.
 */
class PinnedCertificateTlsTest {

    private lateinit var stand: SelfSignedServer

    @Before
    fun setUp() {
        stand = SelfSignedServer().apply { start() }
    }

    @After
    fun tearDown() {
        stand.shutdown()
    }

    @Test
    fun `a self-signed certificate is rejected without a pin`() {
        // Ровно ошибка из issue: доверия цепочке нет, запрос не уходит.
        val client = client { null }

        val failure = runCatching { client.head(stand.url("/").toString()) }.exceptionOrNull()

        assertTrue("ожидался провал TLS, был $failure", failure is SSLException)
    }

    @Test
    fun `a pinned certificate is accepted`() {
        stand.server.enqueue(MockResponse().setResponseCode(200))
        val client = client { stand.fingerprint }

        assertEquals(200, client.head(stand.url("/").toString()))
    }

    @Test
    fun `a pin for another certificate does not help`() {
        // Пин — доверие одному сертификату, а не «выключить проверку».
        val other = CertificateFingerprint.of(HeldCertificate.Builder().build().certificate)
        val client = client { other }

        val failure = runCatching { client.head(stand.url("/").toString()) }.exceptionOrNull()

        assertTrue("человек посередине не проходит", failure is SSLException)
    }

    @Test
    fun `a pin also covers a certificate issued for another name`() {
        // Сервер на голом IP: сертификат не проходит ни цепочку, ни проверку
        // имени. Без исключения для верификатора имени пин был бы бесполезен.
        val nameless = SelfSignedServer(subjectAlternativeName = null).apply { start() }
        nameless.server.enqueue(MockResponse().setResponseCode(204))
        val client = client { nameless.fingerprint }

        try {
            assertEquals(204, client.head(nameless.url("/").toString()))
        } finally {
            nameless.shutdown()
        }
    }

    @Test
    fun `a certificate for another name is still rejected without a pin`() {
        val nameless = SelfSignedServer(subjectAlternativeName = null).apply { start() }
        val client = client { null }

        try {
            val failure = runCatching { client.head(nameless.url("/").toString()) }
                .exceptionOrNull()
            assertTrue(failure is SSLException)
        } finally {
            nameless.shutdown()
        }
    }

    @Test
    fun `the platform verdict comes first and the pin is not even asked for`() {
        // Обычный сервер с сертификатом от системного CA не должен зависеть от
        // пина: делегат сказал «доверяю» — разговор закончен.
        var asked = false
        val trustManager = PinnedCertificateTrustManager(
            pinSource = { asked = true; null },
            delegate = AcceptingTrustManager,
        )

        trustManager.checkServerTrusted(arrayOf(stand.certificate.certificate), "RSA")

        assertFalse("пин спрашивать незачем", asked)
    }

    @Test
    fun `an empty chain is rejected even with a matching pin`() {
        val trustManager = PinnedCertificateTrustManager(
            pinSource = { stand.fingerprint },
            delegate = RejectingTrustManager,
        )

        val failure = runCatching { trustManager.checkServerTrusted(emptyArray(), "RSA") }
            .exceptionOrNull()

        assertTrue("сверять нечего", failure is CertificateException)
    }

    @Test
    fun `the host aware check is used instead of the two argument one`() {
        // На Android платформенный делегат — RootTrustManager из
        // network-security-config, и его двухаргументный checkServerTrusted при
        // наличии <domain-config> (у нас он есть с issue #26) цепочку не
        // проверяет, а сразу кидает CertificateException. Обычный
        // X509TrustManager Conscrypt зовёт именно так — доверять переставал бы
        // любой сервер, включая прод с валидным CA, а release остался бы без
        // сети целиком.
        val delegate = DomainAwareTrustManager()
        val trustManager = PinnedCertificateTrustManager(pinSource = { null }, delegate = delegate)

        trustManager.checkServerTrusted(
            arrayOf(stand.certificate.certificate),
            "RSA",
            null as Socket?,
        )

        assertEquals("вызван host-aware вариант", 1, delegate.hostAwareCalls)
    }

    @Test
    fun `the pin still works through the host aware check`() {
        val delegate = object : X509ExtendedTrustManager() {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) = Unit
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String, s: Socket?) = Unit
            override fun checkClientTrusted(
                c: Array<X509Certificate>,
                a: String,
                e: SSLEngine?,
            ) = Unit

            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) =
                throw CertificateException("двухаргументный вариант звать нельзя")

            override fun checkServerTrusted(c: Array<X509Certificate>, a: String, s: Socket?): Unit =
                throw CertificateException("Trust anchor for certification path not found")

            override fun checkServerTrusted(
                c: Array<X509Certificate>,
                a: String,
                e: SSLEngine?,
            ): Unit = throw CertificateException("Trust anchor for certification path not found")

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val trustManager = PinnedCertificateTrustManager(
            pinSource = { stand.fingerprint },
            delegate = delegate,
        )

        // Не бросает: платформа отказала, но отпечаток совпал с подтверждённым.
        trustManager.checkServerTrusted(
            arrayOf(stand.certificate.certificate),
            "RSA",
            null as Socket?,
        )
    }

    @Test
    fun `accepted issuers stay the platform ones`() {
        // Список уезжает в chain cleaner OkHttp: подменять его нельзя.
        val trustManager = PinnedCertificateTrustManager(pinSource = { stand.fingerprint })

        assertEquals(
            platformTrustManager().acceptedIssuers.size,
            trustManager.acceptedIssuers.size,
        )
    }

    private fun client(pinSource: CertificatePinSource) = NetworkFactory
        .clientBuilder(certificatePin = pinSource)
        .build()

    private fun OkHttpClient.head(url: String): Int =
        newCall(Request.Builder().url(url).head().build()).execute().use { it.code }

    private object AcceptingTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** Платформенный делегат Android в миниатюре: см. `RootTrustManager`. */
    private class DomainAwareTrustManager : X509ExtendedTrustManager() {

        var hostAwareCalls = 0
            private set

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

        override fun checkClientTrusted(
            chain: Array<X509Certificate>,
            authType: String,
            socket: Socket?,
        ) = Unit

        override fun checkClientTrusted(
            chain: Array<X509Certificate>,
            authType: String,
            engine: SSLEngine?,
        ) = Unit

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String): Unit =
            throw CertificateException(
                "Domain specific configurations require that the hostname aware " +
                    "checkServerTrusted(X509Certificate[], String, String) is used",
            )

        override fun checkServerTrusted(
            chain: Array<X509Certificate>,
            authType: String,
            socket: Socket?,
        ) {
            hostAwareCalls++
        }

        override fun checkServerTrusted(
            chain: Array<X509Certificate>,
            authType: String,
            engine: SSLEngine?,
        ) {
            hostAwareCalls++
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private object RejectingTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            throw CertificateException("Trust anchor for certification path not found")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
