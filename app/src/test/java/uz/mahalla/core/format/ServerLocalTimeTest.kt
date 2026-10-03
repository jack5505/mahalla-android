package uz.mahalla.core.format

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime
import java.util.Locale

/**
 * Прямое покрытие [parseServerLocalTime] / [toServerTime] — контракт нигде
 * больше не закреплён (issue #396), а от него зависят часы работы, слоты
 * брони/больницы/игровых зон и талоны.
 */
class ServerLocalTimeTest {

    @Test
    fun `string form accepts HH-mm and HH-mm-ss and trims whitespace`() {
        assertEquals(LocalTime.of(14, 30), parseServerLocalTime("14:30"))
        assertEquals(LocalTime.of(14, 30), parseServerLocalTime("14:30:00"))
        assertEquals(LocalTime.of(9, 5), parseServerLocalTime(" 09:05 "))
    }

    @Test
    fun `string form drops seconds and fractional part`() {
        // Секунды и доли не нужны в расписании — только часы и минуты.
        assertEquals(LocalTime.of(14, 30), parseServerLocalTime("14:30:15.123"))
    }

    @Test
    fun `string form rejects out-of-range hour or minute as null, not an exception`() {
        assertNull(parseServerLocalTime("24:00"))
        assertNull(parseServerLocalTime("12:60"))
    }

    @Test
    fun `string form rejects malformed or absent input`() {
        listOf(null, "abc", "", "14").forEach { raw ->
            assertNull(raw, parseServerLocalTime(raw))
        }
    }

    @Test
    fun `json string is parsed the same way as the plain string form`() {
        assertEquals(LocalTime.of(10, 0), parseServerLocalTime(JsonPrimitive("10:00")))
    }

    @Test
    fun `json non-string primitive is null`() {
        // Springdoc не отдаёт время числом, но если бы отдал — не гадать.
        assertNull(parseServerLocalTime(JsonPrimitive(1430)))
    }

    @Test
    fun `json null is null`() {
        assertNull(parseServerLocalTime(JsonNull))
    }

    @Test
    fun `json object form with all fields`() {
        val json = buildJsonObject {
            put("hour", 9)
            put("minute", 30)
            put("second", 0)
            put("nano", 0)
        }
        assertEquals(LocalTime.of(9, 30), parseServerLocalTime(json))
    }

    @Test
    fun `json object without minute defaults it to zero`() {
        val json = buildJsonObject {
            put("hour", 9)
        }
        assertEquals(LocalTime.of(9, 0), parseServerLocalTime(json))
    }

    @Test
    fun `json object without hour is null`() {
        val json = buildJsonObject {
            put("minute", 30)
        }
        assertNull(parseServerLocalTime(json))
    }

    @Test
    fun `json object with hour as a numeric string is coerced by intOrNull`() {
        // `JsonPrimitive.intOrNull` разбирает `content` независимо от
        // `isString` — код это не проверяет отдельно, значение проходит.
        val json = buildJsonObject {
            put("hour", "9")
            put("minute", 30)
        }
        assertEquals(LocalTime.of(9, 30), parseServerLocalTime(json))
    }

    @Test
    fun `json object with non-numeric hour string is null`() {
        val json = buildJsonObject {
            put("hour", "nine")
        }
        assertNull(parseServerLocalTime(json))
    }

    @Test
    fun `json object out of range is null`() {
        val json = buildJsonObject {
            put("hour", 24)
            put("minute", 0)
        }
        assertNull(parseServerLocalTime(json))
    }

    @Test
    fun `toServerTime formats as HH-mm-ss with zero seconds`() {
        assertEquals("09:05:00", LocalTime.of(9, 5).toServerTime())
    }

    @Test
    fun `toServerTime handles midnight`() {
        assertEquals("00:00:00", LocalTime.MIDNIGHT.toServerTime())
    }

    @Test
    fun `toServerTime drops the source seconds`() {
        assertEquals("14:30:00", LocalTime.of(14, 30, 45).toServerTime())
    }

    @Test
    fun `toServerTime does not depend on the default locale`() {
        // Локали с нелатинскими цифрами (арабская, персидская) заменяют
        // `%d` в String.format на свои — сервер такое не разберёт.
        val original = Locale.getDefault()
        try {
            listOf(Locale.forLanguageTag("ar-SA"), Locale.forLanguageTag("fa-IR")).forEach { locale ->
                Locale.setDefault(locale)
                assertEquals(locale.toLanguageTag(), "09:05:00", LocalTime.of(9, 5).toServerTime())
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `round-trip through the server string preserves minute-precision values`() {
        listOf(
            LocalTime.of(0, 0),
            LocalTime.of(9, 5),
            LocalTime.of(14, 30),
            LocalTime.of(23, 59),
        ).forEach { time ->
            assertEquals(time, parseServerLocalTime(time.toServerTime()))
        }
    }
}
