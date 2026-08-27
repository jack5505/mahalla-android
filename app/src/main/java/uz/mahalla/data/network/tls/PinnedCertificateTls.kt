package uz.mahalla.data.network.tls

import okhttp3.OkHttpClient
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
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
 *
 * Наследник **`X509ExtendedTrustManager`, а не `X509TrustManager`**, и это не
 * стилистика. На Android платформенный делегат — `RootTrustManager` из
 * network-security-config, и его двухаргументный `checkServerTrusted(chain,
 * authType)` цепочку не проверяет вовсе: увидев в конфиге хоть один
 * `<domain-config>` (у нас он есть — cleartext-исключение для loopback из
 * issue #26), он сразу кидает `CertificateException("Domain specific
 * configurations require that the hostname aware checkServerTrusted … is
 * used")`. Обычный `X509TrustManager` Conscrypt зовёт именно так, поэтому
 * доверять переставал бы вообще любой сервер, включая прод с валидным
 * Let's Encrypt, — а в release, где пина нет по построению, приложение просто
 * осталось бы без сети. На JVM этого не видно: тамошний PKIX отвечает на
 * двухаргументный вариант нормально.
 */
class PinnedCertificateTrustManager(
    private val pinSource: CertificatePinSource,
    private val delegate: X509TrustManager = platformTrustManager(),
) : X509ExtendedTrustManager() {

    /**
     * Host-aware делегат, если платформа его дала. Только он получает сокет или
     * `SSLEngine`, из которых берётся имя хоста для per-domain правил.
     */
    private val extended: X509ExtendedTrustManager? = delegate as? X509ExtendedTrustManager

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        delegate.checkClientTrusted(chain, authType)
    }

    override fun checkClientTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        socket: Socket?,
    ) {
        extended?.checkClientTrusted(chain, authType, socket)
            ?: delegate.checkClientTrusted(chain, authType)
    }

    override fun checkClientTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        engine: SSLEngine?,
    ) {
        extended?.checkClientTrusted(chain, authType, engine)
            ?: delegate.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        withPinFallback(chain) { delegate.checkServerTrusted(chain, authType) }
    }

    override fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        socket: Socket?,
    ) {
        withPinFallback(chain) {
            extended?.checkServerTrusted(chain, authType, socket)
                ?: delegate.checkServerTrusted(chain, authType)
        }
    }

    override fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        engine: SSLEngine?,
    ) {
        withPinFallback(chain) {
            extended?.checkServerTrusted(chain, authType, engine)
                ?: delegate.checkServerTrusted(chain, authType)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

    /**
     * Платформенная проверка идёт первой и остаётся главной; пин спрашивается
     * только на её отказе.
     */
    private fun withPinFallback(chain: Array<X509Certificate>, check: () -> Unit) {
        try {
            check()
        } catch (untrusted: CertificateException) {
            val leaf = chain.firstOrNull() ?: throw untrusted
            val pinned = pinSource.pinnedFingerprint()
            if (!CertificateFingerprint.matches(pinned, CertificateFingerprint.of(leaf))) {
                throw untrusted
            }
        }
    }
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
 * Ставится всегда, когда источник пина передан, а не «когда пин уже выдан»:
 * отпечаток читается в момент handshake, поэтому подтверждение доверия
 * действует сразу, без пересборки клиентов. Пока пина нет, поведение ровно то
 * же, что у клиента по умолчанию — обе проверки делают платформенный trust
 * manager и штатный верификатор имени.
 *
 * `pinSource == null` — сборка, которой доверять чужим сертификатам не
 * разрешено (release без `BACKEND_URL_OVERRIDE`, см. `NetworkModule`). Там
 * клиент не трогается вообще: ни своей `sslSocketFactory`, ни своего
 * верификатора имени, то есть остаётся ровно тем, что даёт платформа.
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
