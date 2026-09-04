package uz.mahalla.feature.activity.data

import uz.mahalla.core.format.DateTimeFormatters.AppZone
import uz.mahalla.core.format.parseServerInstant
import uz.mahalla.feature.activity.domain.Activity
import uz.mahalla.feature.activity.domain.ActivityKind
import uz.mahalla.feature.activity.domain.ActivitySource
import uz.mahalla.feature.activity.domain.ActivityStatus
import uz.mahalla.feature.activity.domain.ActivityTarget
import uz.mahalla.feature.food.data.OrderViewDto
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

/**
 * Разбор пяти источников «моих активностей» (issue #73).
 *
 * Разбор мягкий, как в каталоге (issue #53) и в уведомлениях (issue #81):
 * запись **без идентификатора отбрасывается** — в `LazyColumn` она стала бы
 * дубликатом ключа, а отличить её от соседней всё равно нечем. Всё остальное
 * необязательно: активность без даты, без суммы и без статуса остаётся в
 * списке. Пропасть она не должна ни при каких обстоятельствах — за ней стоят
 * потраченные деньги, и «заказ исчез» страшнее «заказ без даты».
 */

/**
 * `last` — главный признак конца; при его отсутствии считаем по
 * `page`/`totalPages`. Полное молчание сервера о страницах останавливает
 * догрузку: лучше не показать хвост, чем зациклить запрос одной и той же
 * страницы (то же правило, что у истории кошелька, issue #62, и уведомлений).
 */
internal fun ActivityPageDto<*>.hasMore(requestedPage: Int): Boolean {
    val pages = totalPages
    return when {
        last != null -> !last
        pages != null -> requestedPage + 1 < pages
        else -> false
    }
}

/**
 * `OrderView` → строка списка.
 *
 * Кликабельны только заказы вертикали «Еда»: экран статуса построен на её
 * домене (этапы кухни, «повторить заказ» кладёт позиции в корзину еды), и
 * заказ одежды открылся бы там под видом заказа еды. Остальные вертикали
 * станут кликабельными вместе со своими экранами.
 */
internal fun OrderViewDto.toActivity(): Activity? {
    val orderId = id?.takeIf { it.isNotBlank() } ?: return null
    val orderKind = ActivityKind.ofOrderVertical(vertical)
    return Activity(
        id = orderId,
        source = ActivitySource.Orders,
        kind = orderKind,
        status = ActivityStatus.ofOrder(status),
        occurredAt = parseServerInstant(createdAt),
        amount = totalAmount,
        // Номер заказа — то, что человек называет в поддержке. Названия
        // заведения в `OrderView` нет вовсе, только `placeId`.
        note = orderNumber?.takeIf { it.isNotBlank() },
        target = if (orderKind == ActivityKind.FoodOrder) {
            ActivityTarget.FoodOrder(orderId)
        } else {
            ActivityTarget.None
        },
    )
}

/**
 * `GamingBooking` → строка списка.
 *
 * Время сортировки — начало брони, а не создание записи: в списке активностей
 * ищут «когда я играю», а не «когда я нажал кнопку». Если `startTime` не
 * приехал, остаётся `createdAt` — иначе бронь ушла бы в конец списка к
 * записям без даты.
 */
internal fun GamingBookingDto.toActivity(): Activity? {
    val bookingId = id?.takeIf { it.isNotBlank() } ?: return null
    return Activity(
        id = bookingId,
        source = ActivitySource.GamingBookings,
        kind = ActivityKind.GamingBooking,
        status = ActivityStatus.ofBooking(status),
        occurredAt = parseServerInstant(startTime) ?: parseServerInstant(createdAt),
        amount = totalPrice,
        // Длительность — единственное, что бэкенд сообщает о брони словами.
        // Подпись («2 ч») собирает экран: строка с числом должна быть
        // локализуемой, а в данных ей делать нечего.
        note = null,
        target = ActivityTarget.None,
    )
}

/**
 * `AppointmentResponse` → строка списка. Одна и та же схема у мастера и у
 * врача, различает их только [source].
 */
internal fun AppointmentDto.toActivity(source: ActivitySource): Activity? {
    val appointmentId = id?.takeIf { it.isNotBlank() } ?: return null
    return Activity(
        id = appointmentId,
        source = source,
        kind = if (source == ActivitySource.DoctorAppointments) {
            ActivityKind.DoctorAppointment
        } else {
            ActivityKind.MasterAppointment
        },
        status = ActivityStatus.ofAppointment(status),
        occurredAt = appointmentAt() ?: parseServerInstant(createdAt),
        amount = price,
        // Название услуги — единственное человекочитаемое поле в ответе, и
        // оно же самое полезное: «Soch olish» говорит больше, чем «Запись».
        note = serviceName?.takeIf { it.isNotBlank() },
        target = ActivityTarget.None,
    )
}

/**
 * Дата и время записи в один момент времени.
 *
 * `apptDate` — местная дата без зоны, поэтому она разворачивается в
 * [AppZone] (Asia/Tashkent), а не в UTC: иначе запись на 09:00 в Ташкенте
 * показывалась бы как 14:00, а запись после 19:00 уезжала бы на следующий
 * день. Время — объект `LocalTime` бэкенда; его отсутствие не повод потерять
 * дату, тогда берётся начало дня.
 */
private fun AppointmentDto.appointmentAt(): Instant? {
    val date = parseLocalDate(apptDate) ?: return null
    val time = startTime?.toLocalTime() ?: LocalTime.MIDNIGHT
    return date.atTime(time).atZone(AppZone).toInstant()
}

private fun parseLocalDate(value: String?): LocalDate? {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) return null
    return try {
        LocalDate.parse(raw)
    } catch (invalid: DateTimeParseException) {
        null
    }
}

/**
 * `{hour, minute, second, nano}` → [LocalTime]. Значения вне суток
 * отбрасываются целиком: собранное из мусора время хуже отсутствующего —
 * человек поверит цифрам на экране.
 */
private fun LocalTimeDto.toLocalTime(): LocalTime? {
    val h = hour ?: return null
    val m = minute ?: 0
    val s = second ?: 0
    if (h !in 0..23 || m !in 0..59 || s !in 0..59) return null
    return LocalTime.of(h, m, s)
}

/**
 * `CinemaTicket` → строка списка.
 *
 * Времени сеанса в ответе нет — только `sessionId`, — поэтому сортировка идёт
 * по времени покупки. Это единственный источник, где так: подставить сеанс
 * неоткуда, пока бэкенд его не отдаёт.
 */
internal fun CinemaTicketDto.toActivity(): Activity? {
    val ticketId = id?.takeIf { it.isNotBlank() } ?: return null
    return Activity(
        id = ticketId,
        source = ActivitySource.CinemaTickets,
        kind = ActivityKind.CinemaTicket,
        status = ActivityStatus.ofTicket(status),
        occurredAt = parseServerInstant(createdAt),
        amount = price,
        // Место в зале: то, что человек ищет в билете в первую очередь.
        note = seatNumber?.takeIf { it.isNotBlank() },
        target = ActivityTarget.None,
    )
}
