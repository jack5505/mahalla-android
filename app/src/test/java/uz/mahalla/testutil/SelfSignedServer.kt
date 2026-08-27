package uz.mahalla.testutil

import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

/**
 * MockWebServer с самоподписанным сертификатом (issue #32).
 *
 * Ровно та ситуация из issue: `https://189.74.96.232/` отдаёт сертификат, чьей
 * цепочке нет доверия, и Android рвёт handshake с `CertPathValidatorException:
 * Trust anchor for certification path not found`. Проверять доверие по
 * отпечатку без такого сервера пришлось бы руками на живом стенде.
 */
class SelfSignedServer(
    /**
     * Имя в сертификате. `null` — сертификат без SAN, как у сервера на голом
     * IP: тогда не проходит и проверка имени хоста, а не только цепочка.
     */
    subjectAlternativeName: String? = "localhost",
) {
    val certificate: HeldCertificate = HeldCertificate.Builder()
        .commonName("mahalla-stand")
        .apply { subjectAlternativeName?.let(::addSubjectAlternativeName) }
        .build()

    /** SHA-256 в том виде, в каком его показывает экран и хранит пин. */
    val fingerprint: String get() = uz.mahalla.data.network.tls.CertificateFingerprint
        .of(certificate.certificate)

    val server: MockWebServer = MockWebServer().apply {
        val handshake = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        useHttps(handshake.sslSocketFactory(), false)
    }

    fun start() = server.start()

    fun shutdown() = server.shutdown()

    fun url(path: String) = server.url(path)
}
