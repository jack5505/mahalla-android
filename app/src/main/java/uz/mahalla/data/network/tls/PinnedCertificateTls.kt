package uz.mahalla.data.network.tls

import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Отпечаток сертификата, которому доверился пользователь (issue #32).
 *
 * Читается на потоке OkHttp во время handshake, поэтому источник обязан
 * отвечать синхронно, без обращения к DataStore — см. `BackendCertificatePin`.
 */
fun interface CertificatePinSource {

    /** SHA-256 доверенного сертификата или `null`, если доверия не выдавали. */
    fun pinnedFingerprint(): String?
}

/**
 * Доверие к одному конкретному сертификату по его отпечатку (issue #32).
 *
 * Зачем: бэкенд разработки живёт на голом IP (`https://189.74.96.232/`), где
 * публичный сертификат получить нельзя, поэтому сертификат самоподписанный, и
 * Android рвёт handshake с `CertPathValidatorException: Trust anchor for
 * certification path not found`.
 *
 * Как это решено: не «выключить проверку сертификатов», а trust-on-first-use,
 * как в ssh. Обычная проверка идёт первой и остаётся главной; сертификат,
 * который её не прошёл, принимается **только** если его отпечаток совпадает с
 * тем, что пользователь подтвердил на экране адреса. Любой другой сертификат —
 * включая другой самоподписанный на том же хосте — по-прежнему отклоняется, то
 * есть человек посередине не проходит.
 *
 * Срок действия пина не проверяется намеренно: даты в сертификате
 * подтверждает CA, которого здесь нет, а доверие выдано конкретному ключу.
 * Иначе просроченный сертификат стенда запирал бы приложение в цикле
 * «не доверенный → доверять → снова не доверенный».
 */
class PinnedCertificateTrustManager(
    private val pinSource: CertificatePinSource,
    private val delegate: X509TrustManager = platformTrustManager(),
) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        delegate.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        try {
            delegate.checkServerTrusted(chain, authType)
        } catch (untrusted: CertificateException) {
            val leaf = chain.firstOrNull() ?: throw untrusted
            val pinned = pinSource.pinnedFingerprint()
            if (!CertificateFingerprint.matches(pinned, CertificateFingerprint.of(leaf))) {
                throw untrusted
            }
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
}

/**
 * Проверка имени хоста с тем же исключением для доверенного сертификата.
 *
 * Без неё пин бесполезен: сертификат, выписанный на голый IP или на другое
 * имя, проходит trust manager, а потом падает с `Hostname … not verified`.
 * Штатная проверка остаётся первой и действует для всех остальных серверов.
 */
class PinnedCertificateHostnameVerifier(
    private val pinSource: CertificatePinSource,
    private val delegate: HostnameVerifier,
) : HostnameVerifier {

    override fun verify(host: String, session: SSLSession): Boolean {
        if (delegate.verify(host, session)) return true
        val pinned = pinSource.pinnedFingerprint() ?: return false
        val leaf = runCatching { session.peerCertificates.firstOrNull() }
            .getOrNull() as? X509Certificate ?: return false
        return CertificateFingerprint.matches(pinned, CertificateFingerprint.of(leaf))
    }
}

/**
 * Разрешает клиенту доверенный сертификат (issue #32).
 *
 * Ставится всегда, а не «когда пин задан»: отпечаток читается в момент
 * handshake, поэтому подтверждение доверия действует сразу, без пересборки
 * клиентов. Пока пина нет, поведение ровно то же, что у клиента по умолчанию —
 * обе проверки делают платформенный trust manager и штатный верификатор имени.
 */
fun OkHttpClient.Builder.allowPinnedCertificate(
    pinSource: CertificatePinSource?,
): OkHttpClient.Builder {
    if (pinSource == null) return this
    val trustManager = PinnedCertificateTrustManager(pinSource)
    return sslSocketFactory(singleTrustManagerSocketFactory(trustManager), trustManager)
        .hostnameVerifier(PinnedCertificateHostnameVerifier(pinSource, defaultHostnameVerifier))
}

/**
 * Штатный верификатор имени OkHttp — берётся у клиента по умолчанию, чтобы не
 * зависеть от `okhttp3.internal` и не переписывать разбор SAN руками. Клиент
 * создаётся один раз и никуда не ходит: нужен только его верификатор.
 */
private val defaultHostnameVerifier: HostnameVerifier by lazy { OkHttpClient().hostnameVerifier }

internal fun singleTrustManagerSocketFactory(
    trustManager: X509TrustManager,
): SSLSocketFactory = SSLContext.getInstance("TLS")
    .apply { init(null, arrayOf(trustManager), SecureRandom()) }
    .socketFactory

/** Trust manager платформы: системные корневые CA плюс установленные пользователем. */
internal fun platformTrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    // null — хранилище по умолчанию, то есть системное.
    factory.init(null as KeyStore?)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        ?: error("платформа не отдала X509TrustManager")
}
