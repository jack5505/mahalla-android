package uz.mahalla.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import uz.mahalla.core.result.runCatchingCancellable
import uz.mahalla.data.network.tls.CertificatePinSource
import uz.mahalla.data.network.tls.CertificateProbe
import uz.mahalla.data.network.tls.ServerCertificate
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

/**
 * Что выяснилось про адрес перед сохранением (issue #26, #32).
 *
 * Три исхода, а не два: «сервер молчит» и «сервер есть, но его сертификату нет
 * доверия» лечатся по-разному, и показывать их одним текстом значит врать про
 * причину. Ровно на этом и застряла issue #32: `SSLHandshakeException` выглядел
 * как «сервер не ответил».
 */
sealed interface BackendCheck {

    /**
     * Сервер ответил. Успех — **любой** HTTP-ответ, включая 404 и 401:
     * корневой путь может ничего не отдавать, но это уже разговор с сервером.
     */
    data object Reachable : BackendCheck

    /** Ответа нет: неизвестный хост, отказ соединения, таймаут. */
    data object Unreachable : BackendCheck

    /**
     * TLS-handshake не прошёл: сертификат не подписан известным CA (обычно
     * самоподписанный) либо выдан на другое имя. Сервер при этом на месте — его
     * сертификат мы только что прочитали.
     */
    data class UntrustedCertificate(val certificate: ServerCertificate) : BackendCheck
}

/**
 * Проверка адреса бэкенда перед сохранением (issue #26).
 *
 * Опечатка в хосте или порту иначе выясняется только на экране входа, где
 * ошибка выглядит как «сеть недоступна» и адрес уже сохранён.
 *
 * Интерфейс — чтобы ViewModel экрана тестировалась без сети.
 */
interface BackendReachability {

    suspend fun check(baseUrl: String): BackendCheck
}

@Singleton
class OkHttpBackendReachability @Inject constructor(
    private val certificatePin: CertificatePinSource,
) : BackendReachability {

    private companion object {
        /** Пользователь ждёт ответа на экране — держать его 15 секунд нельзя. */
        const val TIMEOUT_SECONDS = 5L
        const val TIMEOUT_MILLIS = (TIMEOUT_SECONDS * 1000).toInt()
    }

    /**
     * Свой клиент, а не общий: на общем висит [BackendUrlInterceptor], и
     * проверка уходила бы на текущий адрес вместо проверяемого. Создаётся
     * лениво — проверка случается раз в установку.
     *
     * Пин пользователя учитывается: сертификат, которому уже доверились,
     * должен проходить проверку так же, как он проходит её в бою.
     */
    private val client by lazy {
        NetworkFactory.clientBuilder(certificatePin = certificatePin)
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun check(baseUrl: String): BackendCheck {
        val url = baseUrl.toHttpUrlOrNull() ?: return BackendCheck.Unreachable
        return withContext(Dispatchers.IO) {
            runCatchingCancellable { head(url) }.fold(
                onSuccess = { BackendCheck.Reachable },
                onFailure = { failure ->
                    // Сертификат читаем только когда упал именно TLS: на
                    // таймауте и отказе соединения второй запрос — потерянные
                    // пять секунд ожидания пользователя.
                    if (url.isHttps && failure.isTlsFailure()) {
                        probeCertificate(url)
                    } else {
                        BackendCheck.Unreachable
                    }
                },
            )
        }
    }

    /** HEAD: тело корневого пути может быть большим, а нужен сам факт ответа. */
    private fun head(url: HttpUrl) {
        client.newCall(Request.Builder().url(url).head().build()).execute().close()
    }

    private fun probeCertificate(url: HttpUrl): BackendCheck =
        runCatchingCancellable {
            CertificateProbe.peerCertificate(
                host = url.host,
                port = url.port,
                timeoutMillis = TIMEOUT_MILLIS,
            )
        }.getOrNull()
            ?.let { BackendCheck.UntrustedCertificate(ServerCertificate.of(it)) }
            // Сертификат не прочитался — сказать про него нечего, остаётся
            // общая ошибка: сервер мог оборвать соединение или говорить не по TLS.
            ?: BackendCheck.Unreachable

    /**
     * Провал именно TLS-проверки.
     *
     * `SSLHandshakeException` (нет доверия цепочке) и
     * `SSLPeerUnverifiedException` (сертификат на другое имя) — оба
     * `SSLException`; `CertificateException` приходит от trust manager'а.
     */
    private fun Throwable.isTlsFailure(): Boolean =
        this is SSLException || this is CertificateException ||
            cause?.let { it is SSLException || it is CertificateException } == true
}
