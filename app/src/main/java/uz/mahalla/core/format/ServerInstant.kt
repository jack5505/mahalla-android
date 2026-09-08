package uz.mahalla.core.format

import java.time.Instant
import java.time.LocalDate
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

/**
 * День без времени из ответа бэкенда (`yyyy-MM-dd`): дата записи
 * (`apptDate`, issue #97), день сеанса и дата выхода фильма (issue #106).
 *
 * Разбор такой же мягкий: битая дата — `null`, а не исключение. Запись без
 * дня показывается как есть, теряться из списка ей незачем.
 */
fun parseServerLocalDate(value: String?): LocalDate? {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) return null
    return try {
        LocalDate.parse(raw)
    } catch (invalid: DateTimeParseException) {
        null
    }
}
