package uz.mahalla.data.network.auth

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import uz.mahalla.data.network.NetworkFactory

/**
 * Форма тела `auth/pin-login` (issue #403): бэкенд с 2026-09-07 требует
 * `phone` (`@NotBlank`, `^\+998[0-9]{9}$`) и отвечает `400 VALIDATION_ERROR`
 * без него, не посмотрев на сам PIN. Схему `/v3/api-docs` здесь снять не с
 * чего — бэкенда в этом прогоне нет, — поле зафиксировано по перехвату
 * реального запроса приложения в issue #403 (комментарий от 2026-10-01).
 *
 * Тест ловит ровно то расхождение, которое сломало вход: пропажу поля
 * [PinLoginRequest.phone] при будущей правке DTO.
 */
class PinLoginRequestTest {

    private val json = NetworkFactory.json()

    @Test
    fun `every field the backend requires is in the body`() {
        val request = PinLoginRequest(
            phone = "+998901234567",
            pin = "654321",
            device = DeviceInfoDto(deviceId = "d-1", platform = "ANDROID"),
            lat = 41.31,
            lng = 69.24,
        )

        val body = json.parseToJsonElement(json.encodeToString(request)).jsonObject

        assertEquals("+998901234567", body["phone"]?.jsonPrimitive?.content)
        assertEquals("654321", body["pin"]?.jsonPrimitive?.content)
        assertEquals("d-1", body["device"]?.jsonObject?.get("deviceId")?.jsonPrimitive?.content)
        assertEquals("41.31", body["lat"]?.jsonPrimitive?.content)
        assertEquals("69.24", body["lng"]?.jsonPrimitive?.content)
    }
}
