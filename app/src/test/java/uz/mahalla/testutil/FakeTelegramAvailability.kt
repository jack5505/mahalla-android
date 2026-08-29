package uz.mahalla.testutil

import uz.mahalla.feature.auth.data.TelegramAvailability

/**
 * Наличие Telegram на устройстве — подменяемое. Настоящая реализация ходит в
 * `PackageManager`, который в JVM-тестах заглушен.
 */
class FakeTelegramAvailability(
    var packageName: String? = DEFAULT_PACKAGE,
) : TelegramAvailability {

    override fun installedPackage(): String? = packageName

    companion object {
        const val DEFAULT_PACKAGE = "org.telegram.messenger"
    }
}
