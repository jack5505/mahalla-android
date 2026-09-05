package uz.mahalla.feature.cinema.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.cinema.domain.CinemaTicket
import uz.mahalla.feature.cinema.domain.CinemaTicketPage
import uz.mahalla.feature.cinema.domain.CinemaTicketStatus
import uz.mahalla.feature.cinema.ui.tickets.MyTicketsEvent
import uz.mahalla.feature.cinema.ui.tickets.MyTicketsViewModel
import uz.mahalla.testutil.FakeCinemaRepository
import uz.mahalla.testutil.MainDispatcherRule
import java.time.Instant

/** «Мои билеты» (issue #106): список, догрузка, возврат. */
@OptIn(ExperimentalCoroutinesApi::class)
class MyTicketsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeCinemaRepository()

    @Test
    fun `active tickets come first`() = runTest(mainDispatcherRule.dispatcher) {
        repository.defaultPage = ApiResult.Success(
            CinemaTicketPage(listOf(ticket("used", CinemaTicketStatus.Used), ticket("live"))),
        )

        val viewModel = viewModel()
        runCurrent()

        val tickets = viewModel.state.value.tickets as ScreenState.Content
        assertEquals(listOf("live", "used"), tickets.data.map(CinemaTicket::id))
    }

    @Test
    fun `empty answer is not an error`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        runCurrent()

        assertEquals(ScreenState.Empty, viewModel.state.value.tickets)
    }

    @Test
    fun `failure keeps the server answer for the screen`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.defaultPage = ApiResult.Failure(ApiError.Unauthorized)

            val viewModel = viewModel()
            runCurrent()

            assertEquals(
                ApiError.Unauthorized,
                (viewModel.state.value.tickets as ScreenState.Error).error,
            )
            assertFalse(viewModel.state.value.hasMore)
        }

    /** Кинотеатр мог отметить билет использованным, пока экран был в фоне. */
    @Test
    fun `returning to the screen refreshes the list`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        runCurrent()

        viewModel.onEvent(MyTicketsEvent.ScreenResumed)
        runCurrent()

        assertEquals(listOf(0, 0), repository.requestedPages)
    }

    @Test
    fun `load more appends and deduplicates`() = runTest(mainDispatcherRule.dispatcher) {
        repository.pages[0] = ApiResult.Success(
            CinemaTicketPage(listOf(ticket("t-1"), ticket("t-2")), hasMore = true),
        )
        repository.pages[1] = ApiResult.Success(
            CinemaTicketPage(listOf(ticket("t-2"), ticket("t-3"))),
        )

        val viewModel = viewModel()
        runCurrent()
        viewModel.onEvent(MyTicketsEvent.LoadMore)
        runCurrent()

        val tickets = viewModel.state.value.tickets as ScreenState.Content
        assertEquals(listOf("t-1", "t-2", "t-3"), tickets.data.map(CinemaTicket::id))
        assertFalse(viewModel.state.value.hasMore)
    }

    /** Провал догрузки не стирает список, но и не крутит спиннер вечно. */
    @Test
    fun `failed load more shows a retry with the reason`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.pages[0] = ApiResult.Success(
                CinemaTicketPage(listOf(ticket("t-1")), hasMore = true),
            )
            repository.pages[1] = ApiResult.Failure(ApiError.Timeout)

            val viewModel = viewModel()
            runCurrent()
            viewModel.onEvent(MyTicketsEvent.LoadMore)
            runCurrent()

            val state = viewModel.state.value
            assertTrue(state.tickets is ScreenState.Content)
            assertFalse(state.isLoadingMore)
            assertEquals(ApiError.Timeout, state.loadMoreFailure?.error)
        }

    @Test
    fun `used ticket is not offered for return`() = runTest(mainDispatcherRule.dispatcher) {
        repository.defaultPage = ApiResult.Success(
            CinemaTicketPage(listOf(ticket("used", CinemaTicketStatus.Used))),
        )
        val viewModel = viewModel()
        runCurrent()

        viewModel.onEvent(MyTicketsEvent.CancelRequested("used"))

        assertNull(viewModel.state.value.confirmCancel)
    }

    @Test
    fun `return replaces the ticket in place`() = runTest(mainDispatcherRule.dispatcher) {
        repository.defaultPage = ApiResult.Success(
            CinemaTicketPage(listOf(ticket("t-1"), ticket("t-2"))),
        )
        val viewModel = viewModel()
        runCurrent()

        viewModel.onEvent(MyTicketsEvent.CancelRequested("t-1"))
        assertEquals("t-1", viewModel.state.value.confirmCancel?.id)

        viewModel.onEvent(MyTicketsEvent.CancelConfirmed)
        runCurrent()

        assertEquals(listOf("t-1"), repository.cancelled)
        val tickets = (viewModel.state.value.tickets as ScreenState.Content).data
        // Билет не пропадает — иначе непонятно, вернулось ли что-нибудь.
        assertEquals(2, tickets.size)
        assertEquals(
            CinemaTicketStatus.Cancelled,
            tickets.first { it.id == "t-1" }.status,
        )
        // Списка не перезагружали: догруженный хвост сбросился бы к первой странице.
        assertEquals(listOf(0), repository.requestedPages)
        assertNull(viewModel.state.value.pendingCancelId)
    }

    @Test
    fun `refused return keeps the ticket and shows the reason`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.defaultPage = ApiResult.Success(CinemaTicketPage(listOf(ticket("t-1"))))
            repository.cancelResult = ApiResult.Failure(ApiError.Business("REFUND_WINDOW_CLOSED"))
            val viewModel = viewModel()
            runCurrent()

            viewModel.onEvent(MyTicketsEvent.CancelRequested("t-1"))
            viewModel.onEvent(MyTicketsEvent.CancelConfirmed)
            runCurrent()

            val state = viewModel.state.value
            assertEquals(ApiError.Business("REFUND_WINDOW_CLOSED"), state.cancelFailure?.error)
            assertEquals(
                CinemaTicketStatus.Active,
                (state.tickets as ScreenState.Content).data.single().status,
            )
            assertNull(state.pendingCancelId)
        }

    @Test
    fun `dismissed dialog cancels nothing`() = runTest(mainDispatcherRule.dispatcher) {
        repository.defaultPage = ApiResult.Success(CinemaTicketPage(listOf(ticket("t-1"))))
        val viewModel = viewModel()
        runCurrent()

        viewModel.onEvent(MyTicketsEvent.CancelRequested("t-1"))
        viewModel.onEvent(MyTicketsEvent.CancelDismissed)
        runCurrent()

        assertNull(viewModel.state.value.confirmCancel)
        assertTrue(repository.cancelled.isEmpty())
    }

    private fun ticket(
        id: String,
        status: CinemaTicketStatus = CinemaTicketStatus.Active,
    ) = CinemaTicket(
        id = id,
        seatNumber = "C7",
        priceSum = 45_000,
        code = "4820117499",
        status = status,
        createdAt = Instant.parse("2026-09-04T09:00:00Z"),
    )

    private fun viewModel() = MyTicketsViewModel(repository = repository)
}
