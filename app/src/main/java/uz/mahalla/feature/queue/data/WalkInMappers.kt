package uz.mahalla.feature.queue.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import uz.mahalla.core.format.parseServerInstant
import uz.mahalla.feature.queue.domain.WalkInStatus
import uz.mahalla.feature.queue.domain.WalkInTicket
import java.time.Instant
import java.time.LocalTime

/**
 * Разбор талона мягкий, как в каталоге (issue #53): талон **без `id`** —
 * единственный случай, когда ответ считается негодным. Отменить и показать
 * такой талон нечем, а сделать вид, что записи не было, — тоже неправда:
 * поэтому вызывающий получает `null` и говорит об этом словами.
 *
 * Всё остальное талон не прячет: незнакомый статус становится
 * [WalkInStatus.Unknown], отсутствующая позиция — `null` (у `PENDING` её и не
 * бывает: мастер ещё не подтвердил запись).
 *
 * @param placeName приходит с карточки места: в ответе его нет.
 * @param receivedAt момент, на который состояние известно. Из него экран
 * решает, показывать ли позицию как текущую.
 */
internal fun WalkInDto.toDomain(
    placeId: String,
    placeName: String,
    receivedAt: Instant,
): WalkInTicket? {
    val ticketId = id?.takeIf { it.isNotBlank() } ?: return null
    return WalkInTicket(
        id = ticketId,
        placeId = this.placeId?.takeIf { it.isNotBlank() } ?: placeId,
        placeName = placeName,
        userName = userName?.takeIf { it.isNotBlank() }.orEmpty(),
        serviceName = serviceName?.takeIf { it.isNotBlank() },
        status = WalkInStatus.fromApi(status),
        // Отрицательная позиция — не «минус первый в очереди», а мусор.
        queuePosition = queuePosition?.takeIf { it > 0 },
        estimatedWaitMinutes = estimatedWaitMinutes?.takeIf { it >= 0 },
        counterTime = parseCounterTime(counterTime),
        note = barberNote?.takeIf { it.isNotBlank() },
        createdAt = parseServerInstant(createdAt),
        receivedAt = receivedAt,
    )
}

/**
 * Время, предложенное мастером вместо запрошенного (`COUNTER_OFFERED`).
 *
 * Принимаются оба вида, потому что из схемы стенда не следует, какой приедет:
 * springdoc описывает `LocalTime` объектом `{hour, minute, second, nano}`, а
 * Jackson с `JavaTimeModule` сериализует его строкой `"14:30:00"`. Живым
 * запросом это не проверить — `walkin/send` требует токена.
 *
 * Неразобранное значение — `null`, а не исключение: время-предложение важно,
 * но не настолько, чтобы из-за него терялся весь талон.
 */
internal fun parseCounterTime(value: JsonElement?): LocalTime? =
    when (value) {
        null -> null

        is JsonPrimitive -> value.contentOrNullSafe()?.let(::parseTimeText)

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

private fun JsonPrimitive.contentOrNullSafe(): String? =
    if (isString) content.takeIf { it.isNotBlank() } else null

/** `"14:30"` и `"14:30:00"` — оба вида, что отдаёт Jackson. */
private fun parseTimeText(text: String): LocalTime? {
    val parts = text.trim().split(':')
    if (parts.size < 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in HOUR_RANGE || minute !in MINUTE_RANGE) return null
    return LocalTime.of(hour, minute)
}

private val HOUR_RANGE = 0..23
private val MINUTE_RANGE = 0..59
