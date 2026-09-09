package uz.mahalla.feature.activity.data

import uz.mahalla.core.format.DateTimeFormatters.AppZone
import uz.mahalla.core.format.parseServerInstant
import uz.mahalla.core.format.parseServerLocalTime
import uz.mahalla.feature.activity.domain.Activity
import uz.mahalla.feature.activity.domain.ActivityKind
import uz.mahalla.feature.activity.domain.ActivitySource
import uz.mahalla.feature.activity.domain.ActivityStatus
import uz.mahalla.feature.activity.domain.ActivityTarget
import uz.mahalla.feature.booking.data.AppointmentDto
import uz.mahalla.feature.cinema.data.CinemaTicketDto
import uz.mahalla.feature.food.data.OrderViewDto
import uz.mahalla.feature.gaming.data.GamingBookingDto
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
 *
 * Принимает поля, а не страницу целиком: четыре из пяти `PageResponse…`
 * объявлены в чужих вертикалях и общего надтипа не имеют.
 */
internal fun hasMorePages(last: Boolean?, totalPages: Int?, requestedPage: Int): Boolean = when {
    last != null -> !last
    totalPages != null -> requestedPage + 1 < totalPages
    else -> false
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
 * Запись → строка списка. Схемы у мастера и у врача **разные**
 * (`AppointmentBookingResponse` и `HospitalAppointmentResponse`, сверка со
 * стендом 2026-09-09), но обе читаются одним `AppointmentDto`: поля в нём
 * необязательные, лишние пропускает `ignoreUnknownKeys`. Различает источники
 * [source].
 *
 * Следствие для врача: [amount] и [note] у него всегда пустые — `price` и
 * `serviceName` есть только у мастера, а единственное человекочитаемое поле
 * врача (`complaint`) в общий DTO не входит.
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
 * день. Время бэкенд шлёт то объектом `{hour, minute, …}`, то строкой
 * `"14:30:00"` — оба вида разбирает `parseServerLocalTime`; его отсутствие не
 * повод потерять дату, тогда берётся начало дня.
 */
private fun AppointmentDto.appointmentAt(): Instant? {
    val date = parseLocalDate(apptDate) ?: return null
    val time = parseServerLocalTime(startTime) ?: LocalTime.MIDNIGHT
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
