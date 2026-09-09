package uz.mahalla.feature.activity.data

import uz.mahalla.core.format.DateTimeFormatters.AppZone
import uz.mahalla.core.format.parseServerInstant
import uz.mahalla.core.format.parseServerLocalDate
import uz.mahalla.core.format.parseServerLocalTime
import uz.mahalla.core.format.parseServerSlotInstant
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
import java.time.LocalTime

/**
 * Разбор пяти источников «моих активностей» (issue #73).
 *
 * **Своих DTO у фичи нет** — все пять берутся у вертикалей, которым
 * принадлежат (issue #142): `OrderView` у «Еды», `GamingBooking` у игровых
 * зон, `AppointmentResponse` у брони (её же переиспользует больница),
 * `CinemaTicket` у кино. На бэкенде это по одной схеме на источник, и вторая
 * копия разъезжается с первой при первой же правке контракта — здесь она
 * успела разъехаться ещё до слияния.
 *
 * Разбор мягкий, как в каталоге (issue #53) и в уведомлениях (issue #81):
 * запись **без идентификатора отбрасывается** — в `LazyColumn` она стала бы
 * дубликатом ключа, а отличить её от соседней всё равно нечем. Всё остальное
 * необязательно: активность без даты, без суммы и без статуса остаётся в
 * списке. Пропасть она не должна ни при каких обстоятельствах — за ней стоят
 * потраченные деньги, и «заказ исчез» страшнее «заказ без даты».
 */

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
 * ищут «когда я играю», а не «когда я нажал кнопку». `createdAt` у этой схемы
 * нет вовсе, поэтому бронь без `startTime` остаётся без даты — в конце
 * списка, но в списке.
 *
 * `startTime` разбирает [parseServerSlotInstant], а не [parseServerInstant]:
 * это время слота, местное по построению, ровно как `apptDate` + `startTime` у
 * записи ([appointmentAt]). Две трактовки на один список давали расхождение в
 * пять часов — бронь на 13:00 и запись на 13:00 того же дня оказывались в
 * разных концах порядка «ближайшее сверху» (issue #144).
 */
internal fun GamingBookingDto.toActivity(): Activity? {
    val bookingId = id?.takeIf { it.isNotBlank() } ?: return null
    return Activity(
        id = bookingId,
        source = ActivitySource.GamingBookings,
        kind = ActivityKind.GamingBooking,
        status = ActivityStatus.ofBooking(status),
        occurredAt = parseServerSlotInstant(startTime),
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
 * день. Время разбирает [parseServerLocalTime] — он понимает обе формы
 * `LocalTime`, объект и строку; неразобранное или отсутствующее время не
 * повод потерять дату, тогда берётся начало дня.
 */
private fun AppointmentDto.appointmentAt(): Instant? {
    val date = parseServerLocalDate(apptDate) ?: return null
    val time = parseServerLocalTime(startTime) ?: LocalTime.MIDNIGHT
    return date.atTime(time).atZone(AppZone).toInstant()
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
