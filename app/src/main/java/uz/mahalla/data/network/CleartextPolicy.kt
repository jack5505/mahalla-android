package uz.mahalla.data.network

import android.security.NetworkSecurityPolicy
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Разрешён ли этой сборке незашифрованный `http` на конкретный хост (issue #26).
 *
 * Android с API 28 режет cleartext по умолчанию, и настройка живёт в
 * `res/xml/network_security_config.xml`: в debug-сборке разрешено всё (сервер
 * разработчика в локальной сети без TLS — норма), в release — только loopback
 * и адрес эмулятора. Без этой проверки пользователь вводил бы `http://192.168…`
 * на релизе, а получал бы «сеть недоступна» без единой подсказки.
 *
 * Интерфейс — ради JVM-тестов ViewModel: `NetworkSecurityPolicy` без Android
 * не поднять.
 */
interface CleartextPolicy {

    /** `true`, если по этому адресу вообще можно ходить с текущей сборки. */
    fun isAllowed(url: String): Boolean
}

@Singleton
class AndroidCleartextPolicy @Inject constructor() : CleartextPolicy {

    override fun isAllowed(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (parsed.isHttps) return true
        // Правило задаётся для хоста: конфиг разрешает cleartext точечно.
        return NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(parsed.host)
    }
}
