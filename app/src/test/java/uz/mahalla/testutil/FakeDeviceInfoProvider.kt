package uz.mahalla.testutil

import uz.mahalla.data.device.DeviceDescriptor
import uz.mahalla.data.device.DeviceInfoProvider

/**
 * Описание устройства без Android: `Build.*` в JVM-тестах пустой, а тестам
 * авторизации важно не оно, а то, что запрос вообще несёт устройство.
 */
class FakeDeviceInfoProvider(
    var descriptor: DeviceDescriptor = DEFAULT,
) : DeviceInfoProvider {

    override suspend fun current(): DeviceDescriptor = descriptor

    companion object {
        val DEFAULT = DeviceDescriptor(
            deviceId = "device-1",
            deviceName = "Pixel 8",
            osVersion = "Android 14",
            appVersion = "1.0",
        )
    }
}
