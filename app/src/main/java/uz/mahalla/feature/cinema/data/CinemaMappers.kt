package uz.mahalla.feature.cinema.data

import uz.mahalla.core.format.parseServerInstant
import uz.mahalla.core.format.parseServerLocalDate
import uz.mahalla.core.format.parseServerLocalTime
import uz.mahalla.feature.cinema.domain.CinemaSession
import uz.mahalla.feature.cinema.domain.CinemaTicket
import uz.mahalla.feature.cinema.domain.CinemaTicketPage
import uz.mahalla.feature.cinema.domain.CinemaTicketStatus
import uz.mahalla.feature.cinema.domain.Movie

/**
 * Разбор мягкий, как в каталоге (issue #53): без `id` запись отбрасывается —
 * фильм без него нельзя сопоставить с сеансом, сеанс нельзя купить, билет
 * нельзя вернуть, а в `LazyColumn` любой из них стал бы дубликатом ключа.
 *
 * Всё остальное запись не прячет: без названия фильм получит подпись от
 * экрана, без цены и жанра покажется без них.
 */
internal fun MovieDto.toDomain(): Movie? {
    val movieId = id?.takeIf { it.isNotBlank() } ?: return null
    return Movie(
        id = movieId,
        title = title?.trim()?.takeIf { it.isNotEmpty() }.orEmpty(),
        titleUz = titleUz?.trim()?.takeIf { it.isNotEmpty() },
        description = description?.trim()?.takeIf { it.isNotEmpty() },
        genre = genre?.trim()?.takeIf { it.isNotEmpty() },
        // Ноль и минуса у длительности не бывает — это мусор, а не короткий фильм.
        durationMinutes = durationMinutes?.takeIf { it > 0 },
        releaseDate = parseServerLocalDate(releaseDate),
        posterUrl = posterUrl?.trim()?.takeIf { it.isNotEmpty() },
        trailerUrl = trailerUrl?.trim()?.takeIf { it.isNotEmpty() },
        ageRating = rating?.trim()?.takeIf { it.isNotEmpty() },
        placeId = placeId?.takeIf { it.isNotBlank() },
        // Молчание сервера — «идёт в прокате»: см. KDoc `Movie.isActive`.
        isActive = isActive ?: active ?: true,
    )
}

internal fun CinemaSessionDto.toDomain(): CinemaSession? {
    val sessionId = id?.takeIf { it.isNotBlank() } ?: return null
    return CinemaSession(
        id = sessionId,
        movieId = movieId?.takeIf { it.isNotBlank() },
        placeId = placeId?.takeIf { it.isNotBlank() },
        hallName = hallName?.trim()?.takeIf { it.isNotEmpty() },
        date = parseServerLocalDate(sessionDate),
        startTime = parseServerLocalTime(startTime),
        endTime = parseServerLocalTime(endTime),
        // Отрицательная цена — не скидка, а мусор.
        priceSum = ticketPrice?.coerceAtLeast(0) ?: 0,
        totalSeats = totalSeats?.takeIf { it >= 0 },
        // Отрицательный остаток мест считаем нулём: это «мест нет», а не
        // «сервер промолчал», и предлагать билет на такой сеанс нельзя.
        availableSeats = availableSeats?.coerceAtLeast(0),
        isActive = isActive ?: active ?: true,
    )
}

/**
 * Билет из списка. Без `id` — отбрасывается: вернуть его нечем, а в
 * `LazyColumn` это дубликат ключа.
 *
 * У только что купленного билета правило другое ([toBought]).
 */
internal fun CinemaTicketDto.toDomain(): CinemaTicket? {
    val ticketId = id?.takeIf { it.isNotBlank() } ?: return null
    return ticket(ticketId)
}

/**
 * Только что купленный билет.
 *
 * Ответ без `id` — **не отказ**: билет куплен, и увидеть его можно в «моих
 * билетах» (`GET cinema/tickets/my`). Это разница с талоном очереди (issue
 * #96), где ручки чтения нет вовсе; та же логика, что у записи (issue #97) и
 * у заявки заведения (issue #84).
 */
internal fun CinemaTicketDto.toBought(): CinemaTicket = ticket(id.orEmpty())

private fun CinemaTicketDto.ticket(ticketId: String) = CinemaTicket(
    id = ticketId,
    sessionId = sessionId?.takeIf { it.isNotBlank() },
    seatNumber = seatNumber?.trim()?.takeIf { it.isNotEmpty() },
    priceSum = price?.coerceAtLeast(0) ?: 0,
    code = qrCode?.trim()?.takeIf { it.isNotEmpty() },
    status = CinemaTicketStatus.fromApi(status),
    createdAt = parseServerInstant(createdAt),
)

/** См. [CinemaTicketPage.hasMore] — правило подсчёта живёт там. */
internal fun CinemaTicketPageDto.toDomain(): CinemaTicketPage {
    val pageIndex = page ?: 0
    val pages = totalPages
    return CinemaTicketPage(
        items = content.mapNotNull(CinemaTicketDto::toDomain),
        hasMore = when {
            last != null -> !last
            pages != null -> pageIndex + 1 < pages
            else -> false
        },
    )
}
