package uz.mahalla.feature.business.ui.queue

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.business.domain.QueueAction
import uz.mahalla.feature.business.domain.QueueEntry
import uz.mahalla.feature.queue.domain.WalkInStatus
import uz.mahalla.navigation.BusinessArgs
import uz.mahalla.testutil.FakeBusinessRepository
import uz.mahalla.testutil.MainDispatcherRule
import java.time.Instant

/**
 * Управление очередью (задача 12.2).
 *
 * Проверяется главное для мастера: «вызвать следующего» зовёт того, кого надо,
 * и ни одно действие не уходит на сервер, если состояние талона его не
 * допускает, — иначе отказ бэкенда показывался бы там, где приложение всё
 * знало само.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BusinessQueueViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun `the queue is loaded for the place of the route`() = runTest {
        val repository = FakeBusinessRepository()
        repository.queueResult = ApiResult.Success(listOf(entry("t-1", WalkInStatus.Waiting, 1)))

        val state = viewModel(repository).state.value

        assertEquals(listOf(PLACE), repository.queueRequests)
        assertEquals(1, (state.entries as ScreenState.Content).data.size)
        assertEquals("Barber Studio", state.placeName)
    }

    @Test
    fun `an empty queue is an empty state, not an error`() = runTest {
        val state = viewModel(FakeBusinessRepository()).state.value

        assertTrue(state.entries is ScreenState.Empty)
        assertNull(state.nextInLine)
    }

    @Test
    fun `calling the next one starts the ticket closest to the chair`() = runTest {
        val repository = FakeBusinessRepository()
        repository.queueResult = ApiResult.Success(
            listOf(
                entry("t-far", WalkInStatus.Waiting, position = 4),
                entry("t-near", WalkInStatus.Waiting, position = 1),
            ),
        )
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessQueueEvent.CallNextClicked)

        assertEquals(
            listOf(Triple(PLACE, "t-near", QueueAction.Start)),
            repository.actions,
        )
        val entries = (viewModel.state.value.entries as ScreenState.Content).data
        assertEquals(
            WalkInStatus.InChair,
            entries.first { it.id == "t-near" }.status,
        )
    }

    /** Занятое кресло — не «вызвать», а «завершить»: домен так и решает. */
    @Test
    fun `calling the next one does nothing while somebody is in the chair`() = runTest {
        val repository = FakeBusinessRepository()
        repository.queueResult = ApiResult.Success(
            listOf(
                entry("t-chair", WalkInStatus.InChair),
                entry("t-wait", WalkInStatus.Waiting, position = 1),
            ),
        )
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessQueueEvent.CallNextClicked)

        assertTrue(repository.actions.isEmpty())
    }

    @Test
    fun `a forbidden action does not reach the server`() = runTest {
        val repository = FakeBusinessRepository()
        repository.queueResult = ApiResult.Success(listOf(entry("t-1", WalkInStatus.Completed)))
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessQueueEvent.ActionClicked("t-1", QueueAction.Start))

        assertTrue(repository.actions.isEmpty())
    }

    @Test
    fun `an action on an unknown ticket does not reach the server`() = runTest {
        val repository = FakeBusinessRepository()
        repository.queueResult = ApiResult.Success(listOf(entry("t-1", WalkInStatus.Waiting)))
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessQueueEvent.ActionClicked("t-404", QueueAction.Start))

        assertTrue(repository.actions.isEmpty())
    }

    @Test
    fun `accepting moves the ticket into the queue`() = runTest {
        val repository = FakeBusinessRepository()
        repository.queueResult = ApiResult.Success(listOf(entry("t-1", WalkInStatus.Pending)))
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessQueueEvent.ActionClicked("t-1", QueueAction.Accept))

        val entries = (viewModel.state.value.entries as ScreenState.Content).data
        assertEquals(WalkInStatus.Waiting, entries.single().status)
        assertNull(viewModel.state.value.pendingTicketId)
    }

    /**
     * Строка не исчезает после действия: пропавший ровно в момент нажатия
     * талон читается как «убрал не того».
     */
    @Test
    fun `a completed ticket stays in the list`() = runTest {
        val repository = FakeBusinessRepository()
        repository.queueResult = ApiResult.Success(listOf(entry("t-1", WalkInStatus.InChair)))
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessQueueEvent.ActionClicked("t-1", QueueAction.Complete))

        val entries = (viewModel.state.value.entries as ScreenState.Content).data
        assertEquals(1, entries.size)
        assertEquals(WalkInStatus.Completed, entries.single().status)
    }

    @Test
    fun `a refusal of an action keeps the queue and shows the server message`() = runTest {
        val repository = FakeBusinessRepository()
        repository.queueResult = ApiResult.Success(listOf(entry("t-1", WalkInStatus.Waiting)))
        repository.actResult = { _, _ -> ApiResult.Failure(ApiFailure(ApiError.Forbidden)) }
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessQueueEvent.ActionClicked("t-1", QueueAction.Start))

        val state = viewModel.state.value
        assertEquals(ApiError.Forbidden, state.actionFailure?.error)
        assertEquals(
            WalkInStatus.Waiting,
            (state.entries as ScreenState.Content).data.single().status,
        )
        assertNull(state.pendingTicketId)
    }

    @Test
    fun `a successful action reports itself to the screen`() = runTest {
        val repository = FakeBusinessRepository()
        repository.queueResult = ApiResult.Success(listOf(entry("t-1", WalkInStatus.Waiting)))
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessQueueEvent.ActionClicked("t-1", QueueAction.Start))

        assertEquals(
            BusinessQueueEffect.ActionDone(QueueAction.Start, "t-1"),
            viewModel.effects.first(),
        )
    }

    @Test
    fun `returning to the screen re-reads the queue`() = runTest {
        val repository = FakeBusinessRepository()
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessQueueEvent.ScreenResumed)

        assertEquals(listOf(PLACE, PLACE), repository.queueRequests)
    }

    @Test
    fun `a refusal to load is shown with the server failure`() = runTest {
        val repository = FakeBusinessRepository()
        repository.queueResult = ApiResult.Failure(ApiFailure(ApiError.NoConnection))

        val state = viewModel(repository).state.value

        assertEquals(ApiError.NoConnection, (state.entries as ScreenState.Error).failure.error)
    }

    /**
     * Пока идёт запрос по одному талону, второе действие не уходит: очередь
     * пересчитывается сервером целиком, и ответы приехали бы на список,
     * которого уже нет.
     */
    @Test
    fun `while a request is in flight no second action is sent`() = runTest {
        val repository = FakeBusinessRepository()
        repository.queueResult = ApiResult.Success(
            listOf(entry("t-1", WalkInStatus.Waiting), entry("t-2", WalkInStatus.Waiting)),
        )
        val gate = CompletableDeferred<Unit>()
        val viewModel = viewModel(repository)
        repository.actGate = gate

        viewModel.onEvent(BusinessQueueEvent.ActionClicked("t-1", QueueAction.Start))
        assertTrue(viewModel.state.value.isBusy)
        assertEquals("t-1", viewModel.state.value.pendingTicketId)

        viewModel.onEvent(BusinessQueueEvent.ActionClicked("t-2", QueueAction.Decline))
        assertEquals(1, repository.actions.size)

        gate.complete(Unit)
        assertNull(viewModel.state.value.pendingTicketId)

        // Запрет снимается вместе с запросом: следующее действие проходит.
        viewModel.onEvent(BusinessQueueEvent.ActionClicked("t-2", QueueAction.Decline))
        assertEquals(2, repository.actions.size)
    }

    @Test
    fun `the waiting count skips the finished tickets`() = runTest {
        val repository = FakeBusinessRepository()
        repository.queueResult = ApiResult.Success(
            listOf(
                entry("t-1", WalkInStatus.Waiting),
                entry("t-2", WalkInStatus.Completed),
                entry("t-3", WalkInStatus.Pending),
            ),
        )

        assertEquals(2, viewModel(repository).state.value.waitingCount)
    }

    private fun viewModel(repository: FakeBusinessRepository) = BusinessQueueViewModel(
        repository = repository,
        savedStateHandle = SavedStateHandle(
            mapOf(
                BusinessArgs.PLACE_ID to PLACE,
                BusinessArgs.PLACE_NAME to "Barber Studio",
            ),
        ),
    )

    private fun entry(
        id: String,
        status: WalkInStatus,
        position: Int? = null,
    ) = QueueEntry(
        id = id,
        userName = id,
        status = status,
        queuePosition = position,
        createdAt = Instant.parse("2026-09-09T09:00:00Z"),
    )

    private companion object {
        const val PLACE = FakeBusinessRepository.PLACE_ID
    }
}
