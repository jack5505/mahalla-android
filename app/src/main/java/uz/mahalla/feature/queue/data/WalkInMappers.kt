package uz.mahalla.feature.queue.data

import uz.mahalla.core.format.parseServerInstant
import uz.mahalla.core.format.parseServerLocalTime
import uz.mahalla.feature.queue.domain.WalkInStatus
import uz.mahalla.feature.queue.domain.WalkInTicket
import java.time.Instant

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
        counterTime = parseServerLocalTime(counterTime),
        note = barberNote?.takeIf { it.isNotBlank() },
        createdAt = parseServerInstant(createdAt),
        receivedAt = receivedAt,
    )
}
