package uz.mahalla.testutil

import uz.mahalla.data.network.BackendReachability

/** Проверка адреса без сети: ViewModel экрана тестируется на JVM. */
class FakeBackendReachability(
    var reachable: Boolean = true,
) : BackendReachability {

    /** Адреса, которые проверяли: важно, что проверяется нормализованный. */
    val checked = mutableListOf<String>()

    override suspend fun check(baseUrl: String): Boolean {
        checked += baseUrl
        return reachable
    }
}
