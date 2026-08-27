package uz.mahalla.data.network.tls

import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * Отпечаток сертификата SHA-256 (issue #32).
 *
 * Формат — `AB:CD:…`, тот же, что показывают браузеры и
 * `openssl x509 -noout -fingerprint -sha256`: пользователь должен иметь
 * возможность сверить строку с экрана с тем, что видит на сервере, глазами.
 */
object CertificateFingerprint {

    fun of(certificate: X509Certificate): String = of(certificate.encoded)

    fun of(der: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(der)
        .joinToString(":") { "%02X".format(it) }

    /**
     * Совпадают ли отпечатки.
     *
     * Регистр и двоеточия игнорируются: в хранилище могла лечь строка из
     * прежнего формата, а сравнивать нужно значение, а не запись. Пустой или
     * отсутствующий отпечаток не совпадает ни с чем — иначе «пин не задан»
     * означало бы «доверяем любому сертификату».
     */
    fun matches(expected: String?, actual: String?): Boolean {
        if (expected.isNullOrBlank() || actual.isNullOrBlank()) return false
        return normalize(expected) == normalize(actual)
    }

    private fun normalize(value: String): String =
        value.filterNot { it.isWhitespace() || it == ':' }.uppercase()
}
