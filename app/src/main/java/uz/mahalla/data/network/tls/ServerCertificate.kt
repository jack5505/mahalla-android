package uz.mahalla.data.network.tls

import android.annotation.SuppressLint
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Сертификат, который сервер показал на handshake (issue #32).
 *
 * Нужен ровно для одного: показать пользователю, чему именно ему предлагают
 * доверять. Отпечаток он может сверить с сервером, а `subject`/`issuer`
 * подсказывают, свой это стенд или чужой сервер.
 */
data class ServerCertificate(
    /** SHA-256 в формате `AB:CD:…`. */
    val sha256: String,
    /** Кому выдан (CN/`subject`), как показывает сервер. */
    val subject: String,
    /** Кем выдан. У самоподписанного совпадает с [subject]. */
    val issuer: String,
) {
    companion object {
        fun of(certificate: X509Certificate): ServerCertificate = ServerCertificate(
            sha256 = CertificateFingerprint.of(certificate),
            subject = certificate.subjectX500Principal.name,
            issuer = certificate.issuerX500Principal.name,
        )
    }
}

/**
 * Чтение сертификата, который показывает сервер (issue #32).
 *
 * Только handshake и ничего больше: HTTP-запрос не отправляется вообще, ни
 * заголовков, ни тела, ни токена. Проверки здесь нет по построению — иначе
 * сертификат, из-за которого всё и падает, прочитать было бы нечем. Поэтому
 * результат годится ровно на одно: показать человеку отпечаток и спросить.
 *
 * Почему сокет, а не OkHttp: `Response.handshake.peerCertificates` в OkHttp
 * 4.12 приезжает пустым, когда доверие цепочке не устанавливалось (проверено
 * тестом на самоподписанном MockWebServer) — а нужна именно эта цепочка.
 */
object CertificateProbe {

    // CustomX509TrustManager: проверки здесь нет по построению — иначе прочитать
    // сертификат, из-за которого handshake и падает, было бы нечем. Этот
    // trust manager живёт только внутри `peerCertificate`, HTTP-запрос по такому
    // сокету не уходит, а сетевые клиенты приложения его не видят.
    @SuppressLint("CustomX509TrustManager")
    private val acceptAllTrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val socketFactory by lazy {
        singleTrustManagerSocketFactory(acceptAllTrustManager)
    }

    /**
     * Сертификат сервера или `null`, если до handshake дело не дошло.
     *
     * @param timeoutMillis общий бюджет: пользователь ждёт на экране.
     */
    fun peerCertificate(host: String, port: Int, timeoutMillis: Int): X509Certificate? {
        val plain = Socket()
        try {
            plain.connect(InetSocketAddress(host, port), timeoutMillis)
            plain.soTimeout = timeoutMillis
            // autoClose = true: закрытие TLS-сокета закрывает и обёрнутый.
            // host передаётся именно здесь — из него берётся SNI, без которого
            // сервер с несколькими сертификатами покажет не тот.
            (socketFactory.createSocket(plain, host, port, true) as SSLSocket).use { socket ->
                socket.startHandshake()
                return socket.session.peerCertificates.firstOrNull() as? X509Certificate
            }
        } catch (failure: Throwable) {
            // `use` закрывает только TLS-обёртку, а до неё дело может и не
            // дойти: упасть способны и connect, и сам createSocket. Оставленный
            // сокет — утечка дескриптора на каждой неудачной проверке.
            runCatching { plain.close() }
            throw failure
        }
    }
}
