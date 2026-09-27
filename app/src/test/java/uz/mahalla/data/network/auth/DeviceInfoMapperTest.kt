package uz.mahalla.data.network.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uz.mahalla.data.device.DeviceDescriptor

/**
 * `DeviceDescriptor` → `DeviceInfoDto` (issue #42): без прямого теста до
 * issue #347, хотя это единственное место, где имена полей клиента должны
 * совпасть с именами полей бэкенда.
 */
class DeviceInfoMapperTest {

    @Test
    fun `every field is carried over by its own name, not just fcmToken`() {
        val descriptor = DeviceDescriptor(
            deviceId = "d-1",
            platform = "ANDROID",
            deviceName = "Pixel 7",
            osVersion = "35",
            appVersion = "0.1.0",
            fcmToken = "token-1",
        )

        val dto = descriptor.toDto()

        assertEquals("d-1", dto.deviceId)
        assertEquals("ANDROID", dto.platform)
        assertEquals("Pixel 7", dto.deviceName)
        assertEquals("35", dto.osVersion)
        assertEquals("0.1.0", dto.appVersion)
        assertEquals("token-1", dto.fcmToken)
    }

    @Test
    fun `a missing push token maps to null, not an empty string`() {
        val descriptor = DeviceDescriptor(deviceId = "d-1", fcmToken = null)

        assertNull(descriptor.toDto().fcmToken)
    }
}
