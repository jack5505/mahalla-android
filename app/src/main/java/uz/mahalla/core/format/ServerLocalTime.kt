package uz.mahalla.core.format

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.LocalTime

/**
 * Время без даты из ответа бэкенда (`LocalTime` в его схемах).
 *
 * Принимаются **оба** вида, потому что из схемы стенда не следует, какой
 * приедет: springdoc описывает `LocalTime` объектом `{hour, minute, second,
 * nano}`, а Jackson с `JavaTimeModule` сериализует его строкой `"14:30:00"`.
 * Живым запросом это не проверить — ручки, где такие поля встречаются
 * (`walkin/send` в issue #96, `appointments` в issue #97), требуют токена, а
 * `401` приходит до валидации.
 *
 * Ошибка в типе уронила бы разбор **всей** записи, то есть удачную запись
 * превратила бы в «не удалось». Поэтому неразобранное значение — `null`, а не
 * исключение: время важно, но не настолько, чтобы из-за него терялась сама
 * запись.
 */
fun parseServerLocalTime(value: JsonElement?): LocalTime? = when (value) {
    null -> null

    is JsonPrimitive -> if (value.isString) parseServerLocalTime(value.content) else null

    is JsonObject -> {
        val hour = (value["hour"] as? JsonPrimitive)?.intOrNull
        val minute = (value["minute"] as? JsonPrimitive)?.intOrNull ?: 0
        if (hour == null || hour !in HOUR_RANGE || minute !in MINUTE_RANGE) {
            null
        } else {
            LocalTime.of(hour, minute)
        }
    }

    else -> null
}

/**
 * `"14:30"` и `"14:30:00"` — оба вида, что отдаёт Jackson. Секунды и доли
 * отбрасываются: в расписании заведения их не бывает, а показывать `14:30:00`
 * человеку незачем.
 */
fun parseServerLocalTime(text: String?): LocalTime? {
    val parts = text?.trim()?.split(':') ?: return null
    if (parts.size < 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in HOUR_RANGE || minute !in MINUTE_RANGE) return null
    return LocalTime.of(hour, minute)
}

/** `10:00` — то, как время уходит обратно на сервер (`HH:mm:ss`). */
fun LocalTime.toServerTime(): String = String.format(
    java.util.Locale.ROOT,
    "%02d:%02d:00",
    hour,
    minute,
)

private val HOUR_RANGE = 0..23
private val MINUTE_RANGE = 0..59
