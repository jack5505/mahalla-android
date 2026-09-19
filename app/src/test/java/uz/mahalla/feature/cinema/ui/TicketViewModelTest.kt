package uz.mahalla.feature.cinema.ui

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.cinema.domain.CinemaTicket
import uz.mahalla.feature.cinema.domain.CinemaTicketStatus
import uz.mahalla.feature.cinema.ui.ticket.TicketEvent
import uz.mahalla.feature.cinema.ui.ticket.TicketViewModel
import uz.mahalla.testutil.FakeCinemaRepository
import uz.mahalla.testutil.MainDispatcherRule

/**
 * Карточка билета (issue #183): читается по id при открытии и на
 * pull-to-refresh, а не показывает снимок из списка.
 *
 * Под Robolectric по той же причине, что и карточка фильма: `toRoute()`
 * разбирает маршрут настоящим `Bundle`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TicketViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeCinemaRepository()

    @Test
    fun `ticket is read by id on open`() = runTest(mainDispatcherRule.dispatcher) {
        repository.ticketResult = ApiResult.Success(
            CinemaTicket(id = TICKET, seatNumber = "C7", status = CinemaTicketStatus.Active),
        )

        val viewModel = viewModel()
        runCurrent()

        val state = viewModel.state.value.ticket as ScreenState.Content
        assertEquals("C7", state.data.seatNumber)
        assertEquals(listOf(TICKET), repository.requestedTicketIds)
    }

    /** Билет вернули или сняли на входе, пока человек шёл со списка. */
    @Test
    fun `a missing ticket is an error, not empty`() = runTest(mainDispatcherRule.dispatcher) {
        repository.ticketResult = ApiResult.Failure(ApiError.NotFound)

        val viewModel = viewModel()
        runCurrent()

        assertEquals(ApiError.NotFound, (viewModel.state.value.ticket as ScreenState.Error).error)
    }

    @Test
    fun `pull-to-refresh reads the ticket again without a skeleton`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.ticketResult = ApiResult.Success(CinemaTicket(id = TICKET))
            val viewModel = viewModel()
            runCurrent()

            repository.ticketResult = ApiResult.Success(
                CinemaTicket(id = TICKET, status = CinemaTicketStatus.Used),
            )
            viewModel.onEvent(TicketEvent.Refreshed)
            assertTrue(viewModel.state.value.isRefreshing)
            runCurrent()

            assertFalse(viewModel.state.value.isRefreshing)
            val state = viewModel.state.value.ticket as ScreenState.Content
            assertEquals(CinemaTicketStatus.Used, state.data.status)
            assertEquals(listOf(TICKET, TICKET), repository.requestedTicketIds)
        }

    /** Возврат на экран мог застать билет уже погашенным на входе. */
    @Test
    fun `returning to the screen reloads the ticket`() = runTest(mainDispatcherRule.dispatcher) {
        repository.ticketResult = ApiResult.Success(CinemaTicket(id = TICKET))
        val viewModel = viewModel()
        runCurrent()

        // Первый resume пропускается — это открытие, а не возврат (issue #145).
        viewModel.onEvent(TicketEvent.ScreenResumed)
        viewModel.onEvent(TicketEvent.ScreenResumed)
        runCurrent()

        assertEquals(listOf(TICKET, TICKET), repository.requestedTicketIds)
    }

    @Test
    fun `retry reloads after a failure`() = runTest(mainDispatcherRule.dispatcher) {
        repository.ticketResult = ApiResult.Failure(ApiError.NoConnection)
        val viewModel = viewModel()
        runCurrent()

        repository.ticketResult = ApiResult.Success(CinemaTicket(id = TICKET))
        viewModel.onEvent(TicketEvent.Retry)
        runCurrent()

        assertTrue(viewModel.state.value.ticket is ScreenState.Content)
    }

    private fun viewModel() = TicketViewModel(
        repository = repository,
        savedStateHandle = SavedStateHandle(mapOf("ticketId" to TICKET)),
    )

    private companion object {
        const val TICKET = "t-1"
    }
}
