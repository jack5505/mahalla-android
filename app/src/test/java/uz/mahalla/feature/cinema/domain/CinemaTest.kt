package uz.mahalla.feature.cinema.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Правила вертикали «Кино» (issue #106): что показывать в афише, какие сеансы
 * предлагать, что делать с билетом.
 *
 * Всё это чистые функции — их и надо проверять тестом: ошибку здесь на экране
 * видно не как исключение, а как «сеанса нет» или «кнопка не нажимается».
 */
class CinemaTest {

    // --- Название фильма ---

    @Test
    fun `uzbek interface prefers the uzbek title`() {
        val movie = Movie(id = "m-1", title = "Dune", titleUz = "Qum sayyorasi")

        assertEquals("Qum sayyorasi", movie.displayTitle(preferUzbek = true))
        assertEquals("Dune", movie.displayTitle(preferUzbek = false))
    }

    /** Пустая строка названием не считается: фильм без него не опознать. */
    @Test
    fun `missing translation falls back to the other title`() {
        val onlyOriginal = Movie(id = "m-1", title = "Dune", titleUz = "   ")
        val onlyUzbek = Movie(id = "m-2", title = "", titleUz = "Qum sayyorasi")

        assertEquals("Dune", onlyOriginal.displayTitle(preferUzbek = true))
        assertEquals("Qum sayyorasi", onlyUzbek.displayTitle(preferUzbek = false))
    }

    // --- Афиша ---

    /**
     * Ручка афиши общая на всю платформу, поэтому чужой прокат отсеивается на
     * клиенте, а фильм без заведения остаётся: молчание сервера — не повод
     * спрятать его из всех афиш сразу.
     */
    @Test
    fun `poster keeps own and unattributed movies`() {
        val movies = listOf(
            Movie(id = "mine", title = "A", placeId = PLACE),
            Movie(id = "stranger", title = "B", placeId = "other"),
            Movie(id = "nobody", title = "C", placeId = null),
            Movie(id = "blank", title = "D", placeId = "  "),
        )

        val poster = CinemaPoster.forPlace(movies, PLACE)

        assertEquals(listOf("mine", "nobody", "blank"), poster.map(Movie::id))
    }

    @Test
    fun `poster drops movies out of release`() {
        val movies = listOf(
            Movie(id = "gone", title = "A", placeId = PLACE, isActive = false),
            Movie(id = "running", title = "B", placeId = PLACE),
        )

        assertEquals(listOf("running"), CinemaPoster.forPlace(movies, PLACE).map(Movie::id))
    }

    // --- Сеансы ---

    @Test
    fun `past sessions of today are not offered`() {
        val sessions = listOf(
            session("morning", TODAY, LocalTime.of(10, 0)),
            session("evening", TODAY, LocalTime.of(20, 0)),
        )

        val upcoming = CinemaSchedule.upcoming(sessions, NOW)

        assertEquals(listOf("evening"), upcoming.map(CinemaSession::id))
    }

    /** Отменённого сеанса не будет — это не «нельзя купить». */
    @Test
    fun `cancelled sessions are hidden`() {
        val sessions = listOf(
            session("off", TODAY, LocalTime.of(20, 0)).copy(isActive = false),
            session("on", TODAY, LocalTime.of(21, 0)),
        )

        assertEquals(listOf("on"), CinemaSchedule.upcoming(sessions, NOW).map(CinemaSession::id))
    }

    @Test
    fun `sessions are filtered by movie and sorted by time`() {
        val sessions = listOf(
            session("late", TODAY, LocalTime.of(22, 0)),
            session("other", TODAY, LocalTime.of(19, 0)).copy(movieId = "another"),
            session("early", TODAY, LocalTime.of(18, 0)),
            // Сеанс без фильма приписать не на чем.
            session("orphan", TODAY, LocalTime.of(17, 0)).copy(movieId = null),
        )

        val upcoming = CinemaSchedule.upcoming(sessions, NOW, movieId = MOVIE)

        assertEquals(listOf("early", "late"), upcoming.map(CinemaSession::id))
    }

    /** Без фильтра по фильму сеанс без `movieId` остаётся: он всё же есть. */
    @Test
    fun `session without movie survives an unfiltered schedule`() {
        val sessions = listOf(session("orphan", TODAY, LocalTime.of(20, 0)).copy(movieId = null))

        assertEquals(1, CinemaSchedule.upcoming(sessions, NOW).size)
    }

    @Test
    fun `session of a future day is offered whatever the hour`() {
        val sessions = listOf(session("early", TODAY.plusDays(1), LocalTime.of(9, 0)))

        assertEquals(1, CinemaSchedule.upcoming(sessions, NOW).size)
    }

    /**
     * Время считается в зоне заведения: на телефоне с часами в другой зоне
     * «сегодня» и «уже прошло» иначе разъезжаются на пять часов.
     */
    @Test
    fun `tashkent evening is still today`() {
        // 20:00 UTC = 01:00 следующего дня в Ташкенте.
        val lateNight = Instant.parse("2026-09-04T20:00:00Z")
        val sessions = listOf(session("night", LocalDate.of(2026, 9, 5), LocalTime.of(2, 0)))

        assertEquals(1, CinemaSchedule.upcoming(sessions, lateNight).size)
    }

    @Test
    fun `sold out and started sessions are not bookable`() {
        val soldOut = session("s", TODAY, LocalTime.of(20, 0)).copy(availableSeats = 0)
        val started = session("s", TODAY, LocalTime.of(10, 0))
        val cancelled = session("s", TODAY, LocalTime.of(20, 0)).copy(isActive = false)

        assertFalse(soldOut.isBookable(NOW))
        assertFalse(started.isBookable(NOW))
        assertFalse(cancelled.isBookable(NOW))
        assertTrue(session("s", TODAY, LocalTime.of(20, 0)).isBookable(NOW))
    }

    /**
     * Молчание сервера об остатке мест — не «мест нет»: билет предлагается, а
     * последнее слово остаётся за сервером.
     */
    @Test
    fun `unknown seat count does not block the purchase`() {
        val session = session("s", TODAY, LocalTime.of(20, 0)).copy(availableSeats = null)

        assertFalse(session.isSoldOut)
        assertTrue(session.isBookable(NOW))
    }

    /** Время неизвестно — сеанс не считается начавшимся и остаётся в списке. */
    @Test
    fun `session without time is still offered`() {
        val session = CinemaSession(id = "s", movieId = MOVIE, date = null, startTime = null)

        assertFalse(session.hasStarted(NOW))
        assertTrue(session.isBookable(NOW))
        assertEquals(1, CinemaSchedule.upcoming(listOf(session), NOW, MOVIE).size)
    }

    // --- Место ---

    @Test
    fun `blank seat is not a seat`() {
        assertEquals(null, SeatChoice("   ").seatOrNull())
        assertEquals("C7", SeatChoice("  C7 ").seatOrNull())
        assertTrue(SeatChoice("").canSubmit)
    }

    @Test
    fun `too long seat blocks the purchase`() {
        val long = SeatChoice("x".repeat(SeatChoice.MAX_LENGTH + 1))

        assertTrue(long.isTooLong)
        assertFalse(long.canSubmit)
        assertFalse(SeatChoice("x".repeat(SeatChoice.MAX_LENGTH)).isTooLong)
    }

    // --- Билет ---

    @Test
    fun `ticket statuses are parsed`() {
        assertEquals(CinemaTicketStatus.Active, CinemaTicketStatus.fromApi("ACTIVE"))
        assertEquals(CinemaTicketStatus.Used, CinemaTicketStatus.fromApi(" used "))
        assertEquals(CinemaTicketStatus.Cancelled, CinemaTicketStatus.fromApi("CANCELLED"))
        assertEquals(CinemaTicketStatus.Refunded, CinemaTicketStatus.fromApi("REFUNDED"))
        assertEquals(CinemaTicketStatus.Unknown, CinemaTicketStatus.fromApi("SOMETHING_NEW"))
        assertEquals(CinemaTicketStatus.Unknown, CinemaTicketStatus.fromApi(null))
    }

    /**
     * «Неизвестно, чем кончилось» — не «кончилось»: объявив билет закрытым,
     * экран заодно спрятал бы кнопку возврата.
     */
    @Test
    fun `unknown status is not final and can be returned`() {
        val ticket = CinemaTicket(id = "t-1", status = CinemaTicketStatus.Unknown)

        assertFalse(ticket.isFinal)
        assertTrue(ticket.canCancel)
    }

    @Test
    fun `used and refunded tickets cannot be returned`() {
        assertFalse(CinemaTicket(id = "t", status = CinemaTicketStatus.Used).canCancel)
        assertFalse(CinemaTicket(id = "t", status = CinemaTicketStatus.Refunded).canCancel)
        assertFalse(CinemaTicket(id = "t", status = CinemaTicketStatus.Cancelled).canCancel)
        assertTrue(CinemaTicket(id = "t", status = CinemaTicketStatus.Active).canCancel)
    }

    /** Билет без `id` возвращать нечем — даже если он действует. */
    @Test
    fun `ticket without id cannot be returned`() {
        assertFalse(CinemaTicket(id = "", status = CinemaTicketStatus.Active).canCancel)
    }

    @Test
    fun `active tickets come first and fresh ones on top`() {
        val old = CinemaTicket(
            id = "old",
            status = CinemaTicketStatus.Active,
            createdAt = Instant.parse("2026-09-01T10:00:00Z"),
        )
        val fresh = CinemaTicket(
            id = "fresh",
            status = CinemaTicketStatus.Active,
            createdAt = Instant.parse("2026-09-03T10:00:00Z"),
        )
        val used = CinemaTicket(
            id = "used",
            status = CinemaTicketStatus.Used,
            createdAt = Instant.parse("2026-09-04T10:00:00Z"),
        )
        // Без даты покупки — в конец своей группы, а не наверх.
        val undated = CinemaTicket(id = "undated", status = CinemaTicketStatus.Active)

        val ordered = CinemaTickets.ordered(listOf(used, old, undated, fresh))

        assertEquals(listOf("fresh", "old", "undated", "used"), ordered.map(CinemaTicket::id))
    }

    private fun session(id: String, date: LocalDate, time: LocalTime) = CinemaSession(
        id = id,
        movieId = MOVIE,
        placeId = PLACE,
        date = date,
        startTime = time,
        priceSum = 45_000,
        availableSeats = 10,
    )

    private companion object {
        /** 09:00 UTC = 14:00 в Ташкенте, 4 сентября. */
        val NOW: Instant = Instant.parse("2026-09-04T09:00:00Z")
        val TODAY: LocalDate = LocalDate.of(2026, 9, 4)
        const val PLACE = "11111111-1111-1111-1111-111111111111"
        const val MOVIE = "22222222-2222-2222-2222-222222222222"
    }
}
