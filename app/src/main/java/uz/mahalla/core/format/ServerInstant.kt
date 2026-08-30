package uz.mahalla.core.format

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * Момент времени из ответа бэкенда.
 *
 * Jackson на бэкенде сериализует `LocalDateTime` без зоны
 * (`2026-08-29T16:09:06.688`), а `Instant` — с `Z`; принимаем оба, второй
 * считая временем UTC. Иначе дата пуста у всех — так было у отзывов
 * (issue #53), и так же выглядел бы список устройств (issue #61).
 *
 * Разбор мягкий: неразобранное значение — это `null`, а не исключение. Битое
 * поле в одной записи не должно ронять весь список.
 */
fun parseServerInstant(value: String?): Instant? {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) return null
    return try {
        Instant.parse(raw)
    } catch (invalid: DateTimeParseException) {
        try {
            LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC)
        } catch (invalidLocal: DateTimeParseException) {
            null
        }
    }
}
