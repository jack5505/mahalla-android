package uz.mahalla.feature.cinema.ui.movie

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.cinema.domain.CinemaSession
import uz.mahalla.feature.cinema.domain.CinemaTicket
import uz.mahalla.feature.cinema.domain.Movie
import uz.mahalla.feature.cinema.domain.SeatChoice
import java.time.LocalDate

/**
 * Состояние карточки фильма (issue #106): описание → день → сеанс → покупка.
 *
 * @param movie сам фильм. Ищется в общей афише (`GET cinema/movies`), а не
 * запрашивается по id: `GET cinema/movies/{id}` требует Bearer (`401` без
 * токена — проверено), хотя показывает то же самое, и до входа карточка
 * фильма из-за него была бы недоступна.
 * @param sessions сеансы **этого** кинотеатра на [selectedDate], уже без
 * прошедших и отменённых ([uz.mahalla.feature.cinema.domain.CinemaSchedule]).
 * Отдельным состоянием от [movie]: расписание перезапрашивается на каждый
 * день, а описание фильма при этом мигать не должно.
 * @param purchase сеанс, билет на который человек покупает. Пока он не `null`,
 * открыта шторка покупки — место и кнопка живут там.
 * @param buyFailure отказ покупки вместе с ответом сервера (issue #34).
 * Остаётся **в шторке** рядом с набранным местом: закрыть её значило бы
 * потерять и объяснение, и выбор.
 * @param bought купленный билет: шторка уступает место подтверждению с кодом.
 */
data class MovieState(
    val placeName: String = "",
    val movie: ScreenState<Movie> = ScreenState.Loading,
    val dates: List<LocalDate> = emptyList(),
    val selectedDate: LocalDate? = null,
    val sessions: ScreenState<List<CinemaSession>> = ScreenState.Loading,
    val purchase: CinemaSession? = null,
    val seat: SeatChoice = SeatChoice(),
    val isBuying: Boolean = false,
    val buyFailure: ApiFailure? = null,
    val bought: CinemaTicket? = null,
) : UiState {

    /** Покупать можно только собранный выбор и только один раз. */
    val canBuy: Boolean
        get() = purchase != null && seat.canSubmit && !isBuying && bought == null
}

sealed interface MovieEvent : UiEvent {
    /** Возврат на экран: места разбирают и без участия приложения. */
    data object ScreenResumed : MovieEvent

    data class DateSelected(val date: LocalDate) : MovieEvent
    data object Retry : MovieEvent
    data object SessionsRetry : MovieEvent

    /** Открыть шторку покупки на этот сеанс. */
    data class SessionClicked(val sessionId: String) : MovieEvent
    data object PurchaseDismissed : MovieEvent

    data class SeatChanged(val seat: String) : MovieEvent
    data object BuyClicked : MovieEvent

    /** «Мои билеты» — с подтверждения покупки. */
    data object MyTicketsClicked : MovieEvent
}

sealed interface MovieEffect : UiEffect {
    /**
     * Билет куплен. Экран уходит в «мои билеты»: там билет приезжает уже с
     * сервера — вместе со статусом, который кинотеатр может изменить.
     */
    data object OpenMyTickets : MovieEffect
}
