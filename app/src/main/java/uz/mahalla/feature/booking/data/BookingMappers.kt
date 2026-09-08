package uz.mahalla.feature.booking.data

import uz.mahalla.core.format.parseServerInstant
import uz.mahalla.core.format.parseServerLocalDate
import uz.mahalla.core.format.parseServerLocalTime
import uz.mahalla.feature.booking.domain.Appointment
import uz.mahalla.feature.booking.domain.AppointmentPage
import uz.mahalla.feature.booking.domain.AppointmentStatus
import uz.mahalla.feature.booking.domain.BarberService

/**
 * Разбор мягкий, как в каталоге (issue #53): услуга без `id` отбрасывается —
 * записаться на неё всё равно нечем (`serviceId` обязателен и в запросе
 * слотов, и в самой записи), а в списке она стала бы дубликатом ключа.
 *
 * Всё остальное услугу не прячет: без названия она получит подпись от экрана,
 * без цены и длительности покажется без них. Выключенные услуги
 * (`isActive: false`) сюда доезжают — отсеивает их
 * [BookingRepository.services], чтобы правило было видно в одном месте.
 */
internal fun ServiceDto.toDomain(): BarberService? {
    val serviceId = id?.takeIf { it.isNotBlank() } ?: return null
    return BarberService(
        id = serviceId,
        title = title?.takeIf { it.isNotBlank() }.orEmpty(),
        description = description?.takeIf { it.isNotBlank() },
        // Отрицательная цена — не скидка, а мусор.
        priceSum = priceAmount?.coerceAtLeast(0) ?: 0,
        durationMinutes = durationMinutes?.takeIf { it > 0 },
        // Молчание сервера — «услуга оказывается»: спрятать её из-за
        // отсутствующего флага хуже, чем показать лишнюю.
        isActive = isActive ?: active ?: true,
    )
}

/**
 * Запись из списка. Без `id` — отбрасывается: отменить её нечем, а в
 * `LazyColumn` это дубликат ключа.
 *
 * У только что созданной записи правило другое ([toCreated]) — там отсутствие
 * `id` не отказ.
 */
internal fun AppointmentDto.toDomain(): Appointment? {
    val appointmentId = id?.takeIf { it.isNotBlank() } ?: return null
    return appointment(appointmentId)
}

/**
 * Только что созданная запись.
 *
 * Ответ без `id` — **не отказ**: запись создана, и увидеть её можно в «моих
 * записях», которые приложение всё равно перечитывает у сервера. Это разница с
 * талоном очереди (issue #96), где ручки чтения нет вовсе и такой ответ
 * приходилось считать негодным; та же логика, что у заявки заведения
 * (issue #84).
 */
internal fun AppointmentDto.toCreated(): Appointment = appointment(id.orEmpty())

private fun AppointmentDto.appointment(appointmentId: String) = Appointment(
    id = appointmentId,
    placeId = placeId?.takeIf { it.isNotBlank() },
    serviceId = serviceId?.takeIf { it.isNotBlank() },
    serviceName = serviceName?.takeIf { it.isNotBlank() },
    priceSum = price?.coerceAtLeast(0) ?: 0,
    date = parseServerLocalDate(apptDate),
    startTime = parseServerLocalTime(startTime),
    endTime = parseServerLocalTime(endTime),
    status = AppointmentStatus.fromApi(status),
    createdAt = parseServerInstant(createdAt),
)

/** См. [AppointmentPage.hasMore] — правило подсчёта живёт там. */
internal fun AppointmentPageDto.toDomain(): AppointmentPage {
    val pageIndex = page ?: 0
    val pages = totalPages
    return AppointmentPage(
        items = content.mapNotNull(AppointmentDto::toDomain),
        hasMore = when {
            last != null -> !last
            pages != null -> pageIndex + 1 < pages
            else -> false
        },
    )
}

