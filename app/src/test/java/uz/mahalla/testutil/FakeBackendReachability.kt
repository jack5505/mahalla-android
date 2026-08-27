package uz.mahalla.testutil

import uz.mahalla.data.network.BackendCheck
import uz.mahalla.data.network.BackendReachability

/** Проверка адреса без сети: ViewModel экрана тестируется на JVM. */
class FakeBackendReachability(
    var result: BackendCheck = BackendCheck.Reachable,
) : BackendReachability {

    /** Адреса, которые проверяли: важно, что проверяется нормализованный. */
    val checked = mutableListOf<String>()

    /** Сокращение для случаев, где важен только факт «ответил или нет». */
    var reachable: Boolean
        get() = result == BackendCheck.Reachable
        set(value) {
            result = if (value) BackendCheck.Reachable else BackendCheck.Unreachable
        }

    override suspend fun check(baseUrl: String): BackendCheck {
        checked += baseUrl
        return result
    }
}
