package uz.mahalla.feature.cinema.ui.movie

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.cinema.data.CinemaRepository
import uz.mahalla.feature.cinema.domain.CinemaSchedule
import uz.mahalla.feature.cinema.domain.CinemaSession
import uz.mahalla.feature.cinema.domain.Movie
import uz.mahalla.navigation.MovieRoute
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

/**
 * Карточка фильма и покупка билета (issue #106).
 *
 * Два независимых запроса: афиша (в ней ищется сам фильм) и расписание
 * кинотеатра на выбранный день. Первый делается один раз, второй — на каждый
 * день; провал одного не прячет другого, как баланс и история в кошельке
 * (issue #62).
 */
@HiltViewModel
class MovieViewModel @Inject constructor(
    private val repository: CinemaRepository,
    private val clock: Clock,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<MovieState, MovieEvent, MovieEffect>(MovieState()) {

    private val route: MovieRoute = savedStateHandle.toRoute()

    /**
     * Расписание предыдущего дня. Человек листает дни быстрее, чем отвечает
     * сеть, и ответ на позавчерашний запрос не должен приезжать поверх
     * выбранного сейчас дня.
     */
    private var scheduleJob: Job? = null

    init {
        val dates = CinemaSchedule.dates(clock.instant())
        val today = dates.firstOrNull()
        updateState {
            copy(placeName = route.placeName, dates = dates, selectedDate = today)
        }
        loadMovie()
        if (today != null) loadSchedule(today)
    }

    override fun onEvent(event: MovieEvent) {
        when (event) {
            // Места разбирают и без участия приложения: остаток на сеансе
            // мог измениться, пока экран был в фоне. Афишу перечитывать
            // незачем — описание фильма не меняется за минуту.
            MovieEvent.ScreenResumed -> currentState.selectedDate?.let { date ->
                loadSchedule(date, showLoading = false)
            }

            is MovieEvent.DateSelected -> selectDate(event.date)
            MovieEvent.Retry -> loadMovie()
            MovieEvent.SessionsRetry -> currentState.selectedDate?.let { loadSchedule(it) }

            is MovieEvent.SessionClicked -> openPurchase(event.sessionId)

            MovieEvent.PurchaseDismissed -> updateState {
                copy(purchase = null, seat = seat.copy(seatNumber = ""), buyFailure = null)
            }

            // Отказ снимается на правку: он был про другое место.
            is MovieEvent.SeatChanged -> updateState {
                copy(seat = seat.copy(seatNumber = event.seat), buyFailure = null)
            }

            MovieEvent.BuyClicked -> buy()
            MovieEvent.MyTicketsClicked -> emitEffect(MovieEffect.OpenMyTickets)
        }
    }

    private fun loadMovie() {
        updateState { copy(movie = ScreenState.Loading) }
        viewModelScope.launch {
            val state: ScreenState<Movie> = when (val result = repository.movies()) {
                is ApiResult.Failure -> ScreenState.Error(result.failure)

                is ApiResult.Success -> result.data
                    .firstOrNull { it.id == route.movieId }
                    // Фильма нет в афише — значит его сняли, пока человек шёл
                    // сюда со списка. Это «не найдено», а не пустой экран:
                    // сеансы ниже всё равно грузятся своим запросом.
                    ?.let { movie -> ScreenState.Content(movie) }
                    ?: ScreenState.Error(ApiError.NotFound)
            }
            updateState { copy(movie = state) }
        }
    }

    private fun selectDate(date: LocalDate) {
        if (currentState.selectedDate == date) return
        updateState { copy(selectedDate = date) }
        loadSchedule(date)
    }

    private fun loadSchedule(date: LocalDate, showLoading: Boolean = true) {
        scheduleJob?.cancel()
        if (showLoading) updateState { copy(sessions = ScreenState.Loading) }
        scheduleJob = viewModelScope.launch {
            when (val result = repository.schedule(route.placeId, date)) {
                is ApiResult.Failure -> updateState {
                    copy(sessions = ScreenState.Error(result.failure))
                }

                is ApiResult.Success -> {
                    val upcoming = CinemaSchedule.upcoming(
                        sessions = result.data,
                        now = clock.instant(),
                        movieId = route.movieId,
                    )
                    updateState {
                        copy(
                            sessions = if (upcoming.isEmpty()) {
                                ScreenState.Empty
                            } else {
                                ScreenState.Content(upcoming)
                            },
                            // Сеанс, на который открыта шторка, мог исчезнуть
                            // из нового расписания: покупать больше нечего.
                            purchase = purchase?.let { open ->
                                upcoming.firstOrNull { it.id == open.id }
                            },
                        )
                    }
                }
            }
        }
    }

    /**
     * Шторка покупки. Сеанс берётся из показанного списка, а не из аргумента:
     * нажать можно только по тому, что на экране, а искать его заново — лишний
     * повод разойтись с тем, что человек видел.
     */
    private fun openPurchase(sessionId: String) {
        val session = sessions().firstOrNull { it.id == sessionId } ?: return
        if (!session.isBookable(clock.instant())) return
        updateState {
            copy(
                purchase = session,
                seat = seat.copy(seatNumber = ""),
                buyFailure = null,
                bought = null,
            )
        }
    }

    /**
     * Покупка.
     *
     * Экран после успеха **не уходит** сам: молчаливый переход читается как
     * «ничего не произошло» (issue #49). Показывается билет с его кодом, и уже
     * с него человек идёт в «мои билеты». Расписание при этом перечитывается —
     * мест на сеансе стало меньше, и на экране это должно быть видно.
     */
    private fun buy() {
        val state = currentState
        val session = state.purchase ?: return
        if (!state.canBuy) return

        updateState { copy(isBuying = true, buyFailure = null) }
        viewModelScope.launch {
            when (val result = repository.buy(session.id, state.seat)) {
                is ApiResult.Failure -> updateState {
                    copy(isBuying = false, buyFailure = result.failure)
                }

                is ApiResult.Success -> {
                    updateState {
                        copy(
                            isBuying = false,
                            purchase = null,
                            // Место, которое человек назвал сам, сервер в
                            // ответе может и не повторить — тогда показываем
                            // выбранное: билет без места читается как чужой.
                            bought = result.data.copy(
                                seatNumber = result.data.seatNumber ?: state.seat.seatOrNull(),
                                priceSum = result.data.priceSum.takeIf { it > 0 }
                                    ?: session.priceSum,
                            ),
                        )
                    }
                    currentState.selectedDate?.let { loadSchedule(it, showLoading = false) }
                }
            }
        }
    }

    private fun sessions(): List<CinemaSession> =
        (currentState.sessions as? ScreenState.Content)?.data.orEmpty()
}
