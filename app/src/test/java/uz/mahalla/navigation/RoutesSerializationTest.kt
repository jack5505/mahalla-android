package uz.mahalla.navigation

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Typed routes держатся на kotlinx.serialization: если маршрут перестанет
 * сериализоваться или поле переименуют — сломается навигация и deep link,
 * причём только в рантайме. Эти тесты ловят такое на сборке.
 */
class RoutesSerializationTest {

    private val json = Json

    @Test
    fun `routes with arguments round trip`() {
        val place = PlaceRoute(placeId = "p-42")
        assertEquals(place, json.decodeFromString<PlaceRoute>(json.encodeToString(place)))

        val otp = OtpRoute(phone = "+998901234567")
        assertEquals(otp, json.decodeFromString<OtpRoute>(json.encodeToString(otp)))
    }

    @Test
    fun `graphs and argument-less routes are serializable`() {
        val serialNames = listOf(
            serializer<OnboardingGraph>().descriptor.serialName,
            serializer<MainGraph>().descriptor.serialName,
            serializer<WelcomeRoute>().descriptor.serialName,
            serializer<PhoneRoute>().descriptor.serialName,
            serializer<PinRoute>().descriptor.serialName,
            serializer<BiometricRoute>().descriptor.serialName,
            serializer<GeoRoute>().descriptor.serialName,
            serializer<DiscoveryRoute>().descriptor.serialName,
            serializer<OrdersRoute>().descriptor.serialName,
            serializer<WalletRoute>().descriptor.serialName,
            serializer<ProfileRoute>().descriptor.serialName,
        )
        // Маршруты обязаны быть различимы: одинаковые serialName склеили бы
        // разные destination'ы в один.
        assertEquals(serialNames.size, serialNames.toSet().size)
        serialNames.forEach { assertTrue(it.startsWith("uz.mahalla.navigation.")) }
    }

    @Test
    fun `place deep link placeholder matches the route argument`() {
        val descriptor = serializer<PlaceRoute>().descriptor
        assertEquals(1, descriptor.elementsCount)
        val argumentName = descriptor.getElementName(0)
        assertEquals("placeId", argumentName)
        assertTrue(DeepLinks.PLACE_PATTERN.endsWith("{$argumentName}"))
    }

    @Test
    fun `place deep link is built from the scheme`() {
        assertEquals("mahalla://place/p-42", DeepLinks.place("p-42"))
        assertTrue(DeepLinks.PLACE_PATTERN.startsWith("${DeepLinks.SCHEME}://"))
    }

    @Test
    fun `otp route carries the phone argument`() {
        val descriptor = serializer<OtpRoute>().descriptor
        assertEquals(listOf("phone"), (0 until descriptor.elementsCount).map(descriptor::getElementName))
    }
}
