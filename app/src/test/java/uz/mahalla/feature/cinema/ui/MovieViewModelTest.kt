package uz.mahalla.feature.cinema.ui

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.cinema.domain.CinemaSession
import uz.mahalla.feature.cinema.domain.CinemaTicket
import uz.mahalla.feature.cinema.domain.CinemaTicketStatus
import uz.mahalla.feature.cinema.domain.Movie
import uz.mahalla.feature.cinema.ui.movie.MovieEffect
import uz.mahalla.feature.cinema.ui.movie.MovieEvent
import uz.mahalla.feature.cinema.ui.movie.MovieViewModel
import uz.mahalla.testutil.FakeCinemaRepository
import uz.mahalla.testutil.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Карточка фильма и покупка билета (issue #106).
 *
 * Под Robolectric по той же причине, что и афиша: `toRoute()` разбирает
 * маршрут настоящим `Bundle`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MovieViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeCinemaRepository()

    @Test
    fun `movie is taken from the poster and the day starts as today`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.moviesResult = ApiResult.Success(
                listOf(Movie(id = "other", title = "B"), Movie(id = MOVIE, title = "Dune")),
            )

            val viewModel = viewModel()
            runCurrent()

            val state = viewModel.state.value
            assertEquals("Dune", (state.movie as ScreenState.Content).data.title)
            assertEquals(TODAY, state.selectedDate)
            assertEquals(listOf(TODAY), repository.requestedDays)
        }

    /** Фильм сняли, пока человек шёл сюда со списка: это «не найдено». */
    @Test
    fun `movie missing from the poster is not found`() = runTest(mainDispatcherRule.dispatcher) {
        repository.moviesResult = ApiResult.Success(listOf(Movie(id = "other", title = "B")))

        val viewModel = viewModel()
        runCurrent()

        assertEquals(
            ApiError.NotFound,
            (viewModel.state.value.movie as ScreenState.Error).error,
        )
    }

    /** Расписание приезжает заведением целиком — чужие сеансы отсекаются. */
    @Test
    fun `only sessions of this movie are shown and the past ones are dropped`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.schedules[TODAY] = ApiResult.Success(
                listOf(
                    session("morning", LocalTime.of(10, 0)),
                    session("evening", LocalTime.of(20, 0)),
                    session("foreign", LocalTime.of(21, 0)).copy(movieId = "another"),
                ),
            )

            val viewModel = viewModel()
            runCurrent()

            val sessions = viewModel.state.value.sessions as ScreenState.Content
            assertEquals(listOf("evening"), sessions.data.map(CinemaSession::id))
        }

    @Test
    fun `empty day is not an error`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        runCurrent()

        assertEquals(ScreenState.Empty, viewModel.state.value.sessions)
    }

    @Test
    fun `another day asks the server again`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        runCurrent()

        viewModel.onEvent(MovieEvent.DateSelected(TODAY.plusDays(1)))
        runCurrent()

        assertEquals(listOf(TODAY, TODAY.plusDays(1)), repository.requestedDays)
        // Афиша при смене дня не перечитывается: описание фильма не меняется.
        assertEquals(1, repository.moviesRequests)
    }

    @Test
    fun `the same day is not requested twice`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        runCurrent()

        viewModel.onEvent(MovieEvent.DateSelected(TODAY))
        runCurrent()

        assertEquals(listOf(TODAY), repository.requestedDays)
    }

    /** Сеанс, который уже начался, шторку не открывает: покупать нечего. */
    @Test
    fun `started session does not open the purchase sheet`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.schedules[TODAY] = ApiResult.Success(
                listOf(session("evening", LocalTime.of(20, 0))),
            )
            val viewModel = viewModel()
            runCurrent()

            viewModel.onEvent(MovieEvent.SessionClicked("unknown"))
            assertNull(viewModel.state.value.purchase)

            viewModel.onEvent(MovieEvent.SessionClicked("evening"))
            assertEquals("evening", viewModel.state.value.purchase?.id)
        }

    @Test
    fun `sold out session cannot be bought`() = runTest(mainDispatcherRule.dispatcher) {
        repository.schedules[TODAY] = ApiResult.Success(
            listOf(session("evening", LocalTime.of(20, 0)).copy(availableSeats = 0)),
        )
        val viewModel = viewModel()
        runCurrent()

        viewModel.onEvent(MovieEvent.SessionClicked("evening"))

        assertNull(viewModel.state.value.purchase)
    }

    @Test
    fun `purchase sends the seat and shows the ticket`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = openedSheet()

            viewModel.onEvent(MovieEvent.SeatChanged("C7"))
            viewModel.onEvent(MovieEvent.BuyClicked)
            runCurrent()

            val (sessionId, seat) = repository.bought.single()
            assertEquals("evening", sessionId)
            assertEquals("C7", seat.trimmed)
            val state = viewModel.state.value
            assertEquals("t-1", state.bought?.id)
            assertEquals("C7", state.bought?.seatNumber)
            // Шторка уступает место подтверждению, экран при этом не уходит.
            assertNull(state.purchase)
            assertFalse(state.isBuying)
        }

    /** Место сервер в ответе может и не повторить — показываем выбранное. */
    @Test
    fun `ticket without a seat falls back to the chosen one`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.buyResult = ApiResult.Success(
                CinemaTicket(id = "t-1", status = CinemaTicketStatus.Active),
            )
            val viewModel = openedSheet()

            viewModel.onEvent(MovieEvent.SeatChanged("C7"))
            viewModel.onEvent(MovieEvent.BuyClicked)
            runCurrent()

            assertEquals("C7", viewModel.state.value.bought?.seatNumber)
            // Цена берётся у сеанса, если билет её не назвал.
            assertEquals(45_000L, viewModel.state.value.bought?.priceSum)
        }

    /** Мест на сеансе стало меньше — расписание перечитывается. */
    @Test
    fun `successful purchase refreshes the schedule`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = openedSheet()

        viewModel.onEvent(MovieEvent.BuyClicked)
        runCurrent()

        assertEquals(listOf(TODAY, TODAY), repository.requestedDays)
    }

    @Test
    fun `refusal keeps the sheet with the typed seat`() = runTest(mainDispatcherRule.dispatcher) {
        repository.buyResult = ApiResult.Failure(ApiError.Business("SEAT_TAKEN"))
        val viewModel = openedSheet()

        viewModel.onEvent(MovieEvent.SeatChanged("C7"))
        viewModel.onEvent(MovieEvent.BuyClicked)
        runCurrent()

        val state = viewModel.state.value
        assertEquals("evening", state.purchase?.id)
        assertEquals("C7", state.seat.seatNumber)
        assertEquals(ApiError.Business("SEAT_TAKEN"), state.buyFailure?.error)
        assertNull(state.bought)

        // Правка снимает отказ: он был про другое место.
        viewModel.onEvent(MovieEvent.SeatChanged("C8"))
        assertNull(viewModel.state.value.buyFailure)
    }

    /** Слишком длинное место кнопку не включает. */
    @Test
    fun `too long seat blocks the button`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = openedSheet()

        viewModel.onEvent(MovieEvent.SeatChanged("x".repeat(64)))

        assertFalse(viewModel.state.value.canBuy)
        viewModel.onEvent(MovieEvent.BuyClicked)
        runCurrent()
        assertTrue(repository.bought.isEmpty())
    }

    @Test
    fun `second tap does not buy a second ticket`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = openedSheet()

        viewModel.onEvent(MovieEvent.BuyClicked)
        viewModel.onEvent(MovieEvent.BuyClicked)
        runCurrent()

        assertEquals(1, repository.bought.size)
    }

    @Test
    fun `my tickets is reachable from the bought ticket`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel()
            runCurrent()

            val effects = mutableListOf<MovieEffect>()
            val job = launch { effects += viewModel.effects.first() }
            viewModel.onEvent(MovieEvent.MyTicketsClicked)
            runCurrent()
            job.join()

            assertEquals(MovieEffect.OpenMyTickets, effects.single())
        }

    /** Возврат на экран перечитывает расписание, но не афишу. */
    @Test
    fun `returning refreshes only the schedule`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        runCurrent()

        viewModel.onEvent(MovieEvent.ScreenResumed)
        runCurrent()

        assertEquals(listOf(TODAY, TODAY), repository.requestedDays)
        assertEquals(1, repository.moviesRequests)
    }

    /** Сеанс исчез из нового расписания — шторка закрывается сама. */
    @Test
    fun `sheet closes when the session disappears`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = openedSheet()

        repository.schedules[TODAY] = ApiResult.Success(emptyList())
        viewModel.onEvent(MovieEvent.ScreenResumed)
        runCurrent()

        assertNull(viewModel.state.value.purchase)
    }

    /** Экран с открытой шторкой покупки на вечерний сеанс. */
    private fun TestScope.openedSheet(): MovieViewModel {
        repository.schedules[TODAY] = ApiResult.Success(
            listOf(session("evening", LocalTime.of(20, 0))),
        )
        val viewModel = viewModel()
        runCurrent()
        viewModel.onEvent(MovieEvent.SessionClicked("evening"))
        return viewModel
    }

    private fun session(id: String, time: LocalTime) = CinemaSession(
        id = id,
        movieId = MOVIE,
        placeId = PLACE,
        date = TODAY,
        startTime = time,
        priceSum = 45_000,
        availableSeats = 10,
    )

    private fun viewModel() = MovieViewModel(
        repository = repository,
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
        savedStateHandle = SavedStateHandle(
            mapOf("placeId" to PLACE, "movieId" to MOVIE, "placeName" to "Cinema Park"),
        ),
    )

    private companion object {
        /** 09:00 UTC = 14:00 в Ташкенте, 4 сентября. */
        val NOW: Instant = Instant.parse("2026-09-04T09:00:00Z")
        val TODAY: LocalDate = LocalDate.of(2026, 9, 4)
        const val PLACE = "p-1"
        const val MOVIE = "m-1"
    }
}
