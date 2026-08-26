package uz.mahalla.testutil

import uz.mahalla.data.network.CleartextPolicy

/**
 * Политика cleartext без Android: `NetworkSecurityPolicy` в JVM-тестах не
 * поднять, а поведение сборки, которая режет `http`, проверить надо.
 */
class FakeCleartextPolicy(
    /** `false` — сборка ходит только по https (обычный release). */
    var allowCleartext: Boolean = true,
) : CleartextPolicy {

    override fun isAllowed(url: String): Boolean =
        allowCleartext || url.startsWith("https://", ignoreCase = true)
}
