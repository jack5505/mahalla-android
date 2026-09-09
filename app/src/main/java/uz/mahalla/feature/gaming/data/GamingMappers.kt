package uz.mahalla.feature.gaming.data

import uz.mahalla.core.format.DateTimeFormatters
import uz.mahalla.core.format.parseServerSlotInstant
import uz.mahalla.feature.gaming.domain.GamingBooking
import uz.mahalla.feature.gaming.domain.GamingBookingPage
import uz.mahalla.feature.gaming.domain.GamingBookingStatus
import uz.mahalla.feature.gaming.domain.GamingZone
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Разбор мягкий, как в каталоге (issue #53): зона **без `id`** отбрасывается —
 * забронировать её всё равно нечем, а в `LazyColumn` она стала бы дубликатом
 * ключа.
 *
 * Всё остальное зону не прячет: без имени она получит подпись от экрана, без
 * цены — станет непригодной для брони ([GamingZone.isBookable]), но останется
 * видимой. Пропасть из списка зона не должна ни в одном из этих случаев:
 * человек пришёл посмотреть, что есть в клубе.
 *
 * @param placeId заведение из маршрута: в ответе поле есть, но полагаться
 * только на него нельзя — зона без `placeId` иначе оказалась бы ничьей.
 */
internal fun GamingZoneDto.toDomain(placeId: String): GamingZone? {
    val zoneId = id?.takeIf { it.isNotBlank() } ?: return null
    return GamingZone(
        id = zoneId,
        placeId = this.placeId?.takeIf { it.isNotBlank() } ?: placeId,
        name = name?.takeIf { it.isNotBlank() }.orEmpty(),
        description = description?.takeIf { it.isNotBlank() },
        zoneType = zoneType?.takeIf { it.isNotBlank() },
        // Отрицательная цена — не «скидка», а мусор.
        pricePerHour = pricePerHour?.takeIf { it > 0 } ?: 0,
        totalSeats = totalSeats?.takeIf { it > 0 },
        // Молчание сервера — «закрыта»: обещать бронь зоны, про которую ничего
        // не известно, хуже, чем её не обещать.
        isAvailable = isAvailable ?: available ?: false,
    )
}

/**
 * Бронь из ответа.
 *
 * @param requireId для списка `true`: запись без `id` — дубликат ключа в
 * `LazyColumn`. Для только что созданной брони `false`: ответ без `id` — **не
 * отказ**, бронь принята, а идентификатор приложению пока некуда девать
 * (отменять брони нечем — см. [GamingApi]). Тот же приём, что у заявки
 * продавца в issue #84.
 */
internal fun GamingBookingDto.toDomain(
    zoneName: String = "",
    requireId: Boolean = true,
): GamingBooking? {
    val bookingId = id?.takeIf { it.isNotBlank() }
    if (bookingId == null && requireId) return null
    return GamingBooking(
        id = bookingId.orEmpty(),
        zoneId = zoneId?.takeIf { it.isNotBlank() }.orEmpty(),
        placeId = placeId?.takeIf { it.isNotBlank() }.orEmpty(),
        zoneName = zoneName,
        // Время слота, а не отметка сервера: зоне-менее строка — местное
        // ташкентское время, см. `parseServerSlotInstant` (issue #144).
        startTime = parseServerSlotInstant(startTime),
        endTime = parseServerSlotInstant(endTime),
        durationHours = durationHours?.takeIf { it > 0 },
        totalPrice = totalPrice?.takeIf { it >= 0 },
        status = GamingBookingStatus.fromApi(status),
    )
}

/** См. `MyPlacePage` (issue #94) — правило подсчёта страниц там же. */
internal fun GamingBookingPageDto.toDomain(): GamingBookingPage {
    val pageIndex = page ?: 0
    val pages = totalPages
    return GamingBookingPage(
        items = content.mapNotNull { it.toDomain() },
        hasMore = when {
            last != null -> !last
            pages != null -> pageIndex + 1 < pages
            // Полное молчание о страницах останавливает догрузку: лучше не
            // показать хвост, чем крутить одну страницу в цикле.
            else -> false
        },
    )
}

/**
 * Время начала для тела запроса.
 *
 * Формат — ISO без зоны (`2026-09-05T18:30:00`), потому что ровно так бэкенд
 * отдаёт время в своих ответах (Jackson и `LocalDateTime`), а поле со
 * смещением такой тип принял бы не везде.
 *
 * **Зона — Asia/Tashkent, и это решение, а не мелочь.** Слот — местное время
 * заведения: человек выбрал 13:00 по часам на стене, и такой же его ждёт
 * запись к мастеру (`apptDate` + `startTime`, местные по построению). Одна
 * трактовка на оба источника — условие того, чтобы бронь и запись на 13:00
 * встали в «Моих активностях» рядом, а не в пяти часах друг от друга
 * (issue #144). Отправка и чтение — две стороны одного решения: пара к этой
 * функции — `parseServerSlotInstant`, менять их можно только вместе.
 *
 * До issue #144 здесь был UTC — тоже замкнуто (что ушло, то и вернулось), но
 * бронь, созданную кем-то другим (бизнес-панель, сам бэкенд), приложение
 * читало на пять часов позже, а заведение видело у себя 08:00 вместо 13:00.
 */
internal fun gamingRequestTime(instant: Instant): String =
    REQUEST_TIME_PATTERN.format(instant.atZone(DateTimeFormatters.AppZone))

/**
 * Секунды пишутся всегда: `ISO_LOCAL_DATE_TIME` их опускает, когда они нули, а
 * приложение отправляет ровно такие моменты — получасовую сетку. Строка без
 * секунд валидна для `LocalDateTime`, но разбирается не всяким парсером, и
 * зависеть от этого незачем.
 */
private val REQUEST_TIME_PATTERN: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ROOT)
