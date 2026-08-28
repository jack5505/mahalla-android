package uz.mahalla.data.device

import android.os.Build
import uz.mahalla.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Описание устройства для бэкенда: он заводит на него сессию, показывает её в
 * списке «мои устройства» и требует `deviceId` с `platform` в каждом запросе
 * авторизации (`send-otp`, `verify-otp`, `refresh`).
 */
data class DeviceDescriptor(
    /** Стабильный идентификатор установки — см. [DeviceIdStore]. */
    val deviceId: String,
    val platform: String = PLATFORM_ANDROID,
    val deviceName: String? = null,
    val osVersion: String? = null,
    val appVersion: String? = null,
    /** Токен пуш-уведомлений. FCM в приложении пока нет — всегда `null`. */
    val fcmToken: String? = null,
) {
    companion object {
        const val PLATFORM_ANDROID = "ANDROID"
    }
}

/**
 * Интерфейс, а не класс: `Build.*` в JVM-тестах пустой, а собирать описание
 * устройства нужно и репозиторию авторизации, и `TokenAuthenticator` —
 * подменяемая реализация избавляет оба теста от Robolectric.
 */
interface DeviceInfoProvider {
    suspend fun current(): DeviceDescriptor
}

@Singleton
class AndroidDeviceInfoProvider @Inject constructor(
    private val deviceIdStore: DeviceIdStore,
) : DeviceInfoProvider {

    override suspend fun current(): DeviceDescriptor = DeviceDescriptor(
        deviceId = deviceIdStore.deviceId(),
        deviceName = deviceName(),
        osVersion = "Android ${Build.VERSION.RELEASE.orEmpty()}".trim(),
        appVersion = BuildConfig.VERSION_NAME,
    )

    /**
     * «Samsung SM-A536B». Пустые части отбрасываются: на части прошивок
     * `MANUFACTURER` совпадает с началом `MODEL` или пуст вовсе, и в списке
     * устройств пользователь видел бы «null null».
     */
    private fun deviceName(): String? = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { !it.isNullOrBlank() }
        .joinToString(separator = " ")
        .takeIf { it.isNotBlank() }
}
