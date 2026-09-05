package uz.mahalla.feature.cinema.data

import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.map
import uz.mahalla.data.network.ensureSuccess
import uz.mahalla.data.network.payload
import uz.mahalla.feature.cinema.domain.CinemaSession
import uz.mahalla.feature.cinema.domain.CinemaTicket
import uz.mahalla.feature.cinema.domain.CinemaTicketPage
import uz.mahalla.feature.cinema.domain.CinemaTicketStatus
import uz.mahalla.feature.cinema.domain.Movie
import uz.mahalla.feature.cinema.domain.SeatChoice
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Вертикаль «Кино» (issue #106): афиша, расписание, покупка, свои билеты,
 * возврат.
 *
 * Кэша нет намеренно — ни у афиши, ни у расписания, ни у билетов: остаток
 * мест меняется в течение часа, а `ACTIVE` из Room после возврата был бы
 * прямой ложью.
 *
 * Интерфейс — ради тестов ViewModel: экраны проверяются без MockWebServer.
 */
interface CinemaRepository {

    /**
     * Афиша платформы целиком. Отбирать фильмы конкретного кинотеатра —
     * работа домена (`CinemaPoster.forPlace`): фильтра по заведению у ручки
     * нет.
     */
    suspend fun movies(): ApiResult<List<Movie>>

    /** Расписание кинотеатра на один день. */
    suspend fun schedule(placeId: String, date: LocalDate): ApiResult<List<CinemaSession>>

    /** Купить билет на сеанс. Место необязательно — см. [SeatChoice]. */
    suspend fun buy(sessionId: String, seat: SeatChoice): ApiResult<CinemaTicket>

    /** Свои билеты, страницами. */
    suspend fun myTickets(page: Int = 0, size: Int = PAGE_SIZE): ApiResult<CinemaTicketPage>

    /** Вернуть билет. */
    suspend fun cancel(ticket: CinemaTicket): ApiResult<CinemaTicket>

    companion object {
        const val PAGE_SIZE = 20

        /** Запрос, который заведомо отвергнет сервер, в сеть не уходит. */
        const val INVALID_REQUEST_CODE = "CINEMA_REQUEST_INVALID"
    }
}

@Singleton
class DefaultCinemaRepository @Inject constructor(
    private val api: CinemaApi,
) : CinemaRepository {

    override suspend fun movies(): ApiResult<List<Movie>> =
        apiCall { api.movies().payload() }
            .map { movies -> movies.mapNotNull(MovieDto::toDomain) }

    override suspend fun schedule(
        placeId: String,
        date: LocalDate,
    ): ApiResult<List<CinemaSession>> = apiCall {
        api.schedule(placeId = placeId, date = date.toString()).payload()
    }.map { sessions -> sessions.mapNotNull(CinemaSessionDto::toDomain) }

    /**
     * Покупка.
     *
     * Слишком длинное место в сеть не уходит: сервер ответил бы тем же
     * отказом, но платой были бы запрос и молчание экрана на время его
     * выполнения.
     *
     * Ответ без `id` отказом **не** считается — билет куплен, а увидеть его
     * можно в «моих билетах» (см. [CinemaTicketDto.toBought]).
     */
    override suspend fun buy(sessionId: String, seat: SeatChoice): ApiResult<CinemaTicket> {
        if (sessionId.isBlank() || !seat.canSubmit) {
            return ApiResult.Failure(ApiError.Business(CinemaRepository.INVALID_REQUEST_CODE))
        }

        return apiCall {
            api.buy(
                sessionId = sessionId,
                body = BuyTicketRequest(seatNumber = seat.seatOrNull()),
            ).payload()
        }.map(CinemaTicketDto::toBought)
    }

    override suspend fun myTickets(page: Int, size: Int): ApiResult<CinemaTicketPage> =
        apiCall { api.myTickets(page = page.coerceAtLeast(0), size = size).payload() }
            .map(CinemaTicketPageDto::toDomain)

    /**
     * Возврат. Ответ — тот же билет, но обязательным его разбор не считаем:
     * `ensureSuccess()` уже подтвердил, что сервер вернул именно этот. Если
     * годного тела не окажется, состояние выводится из факта возврата — иначе
     * удачный возврат выглядел бы как «вернуть не удалось» (та же грабля, что
     * у заказов еды, issue #9, и у записей, issue #97).
     */
    override suspend fun cancel(ticket: CinemaTicket): ApiResult<CinemaTicket> {
        if (ticket.id.isBlank()) {
            return ApiResult.Failure(ApiError.Business(CinemaRepository.INVALID_REQUEST_CODE))
        }

        return apiCall {
            val response = api.cancel(ticket.id)
            response.ensureSuccess()
            response.data
        }.map { dto ->
            dto?.toDomain() ?: ticket.copy(status = CinemaTicketStatus.Cancelled)
        }
    }
}
