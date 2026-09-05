package uz.mahalla.testutil

import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.cinema.data.CinemaRepository
import uz.mahalla.feature.cinema.domain.CinemaSession
import uz.mahalla.feature.cinema.domain.CinemaTicket
import uz.mahalla.feature.cinema.domain.CinemaTicketPage
import uz.mahalla.feature.cinema.domain.CinemaTicketStatus
import uz.mahalla.feature.cinema.domain.Movie
import uz.mahalla.feature.cinema.domain.SeatChoice
import java.time.LocalDate

/**
 * Кино в памяти (issue #106): экраны проверяются без MockWebServer.
 *
 * Расписание и страницы билетов задаются по ключу — иначе не отличить смену
 * дня от повторной загрузки того же, а догрузку от перезагрузки первой
 * страницы.
 */
class FakeCinemaRepository : CinemaRepository {

    var moviesResult: ApiResult<List<Movie>> = ApiResult.Success(emptyList())

    var moviesRequests = 0

    val schedules: MutableMap<LocalDate, ApiResult<List<CinemaSession>>> = mutableMapOf()

    var defaultSchedule: ApiResult<List<CinemaSession>> = ApiResult.Success(emptyList())

    /** Дни, на которые запрашивали расписание, — по порядку запросов. */
    val requestedDays = mutableListOf<LocalDate>()

    var buyResult: ApiResult<CinemaTicket>? = null

    /** Что уходило в `buy`: сеанс и выбранное место. */
    val bought = mutableListOf<Pair<String, SeatChoice>>()

    val pages: MutableMap<Int, ApiResult<CinemaTicketPage>> = mutableMapOf()

    var defaultPage: ApiResult<CinemaTicketPage> = ApiResult.Success(CinemaTicketPage())

    val requestedPages = mutableListOf<Int>()

    /** Исход возврата; `null` — вернуть тот же билет со статусом «отменён». */
    var cancelResult: ApiResult<CinemaTicket>? = null

    val cancelled = mutableListOf<String>()

    override suspend fun movies(): ApiResult<List<Movie>> {
        moviesRequests++
        return moviesResult
    }

    override suspend fun schedule(
        placeId: String,
        date: LocalDate,
    ): ApiResult<List<CinemaSession>> {
        requestedDays += date
        return schedules[date] ?: defaultSchedule
    }

    override suspend fun buy(sessionId: String, seat: SeatChoice): ApiResult<CinemaTicket> {
        bought += sessionId to seat
        return buyResult ?: ApiResult.Success(
            CinemaTicket(
                id = "t-1",
                sessionId = sessionId,
                seatNumber = seat.seatOrNull(),
                code = "4820117499",
                status = CinemaTicketStatus.Active,
            ),
        )
    }

    override suspend fun myTickets(page: Int, size: Int): ApiResult<CinemaTicketPage> {
        requestedPages += page
        return pages[page] ?: defaultPage
    }

    override suspend fun cancel(ticket: CinemaTicket): ApiResult<CinemaTicket> {
        cancelled += ticket.id
        return cancelResult
            ?: ApiResult.Success(ticket.copy(status = CinemaTicketStatus.Cancelled))
    }
}
