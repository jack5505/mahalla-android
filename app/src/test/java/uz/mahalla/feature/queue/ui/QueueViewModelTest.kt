package uz.mahalla.feature.queue.ui

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import uz.mahalla.data.prefs.UserProfile
import uz.mahalla.feature.queue.domain.WalkInRequestError
import uz.mahalla.feature.queue.domain.WalkInStatus
import uz.mahalla.testutil.FakeUserProfileStore
import uz.mahalla.testutil.FakeWalkInRepository
import uz.mahalla.testutil.MainDispatcherRule
import uz.mahalla.testutil.walkInTicket
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Экран очереди (issue #96): запись, талон, отмена.
 *
 * Под Robolectric, потому что `SavedStateHandle.toRoute()` разбирает
 * типизированный маршрут через настоящий `Bundle` — на голой JVM аргументы
 * читаются как `null`, причём молча.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class QueueViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeWalkInRepository()
    private val profileStore = FakeUserProfileStore(UserProfile(fullName = "Jahongir Sabirov"))

    @Test
    fun `the name is prefilled from the profile`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        runCurrent()

        // Набирать заново то, что приложение уже знает, — самый быстрый способ
        // получить брошенную форму.
        assertEquals("Jahongir Sabirov", viewModel.state.value.request.userName)
        assertEquals("p-1", viewModel.state.value.request.placeId)
        assertEquals("Barber House", viewModel.state.value.placeName)
        assertFalse(viewModel.state.value.isLoading)
    }

    @Test
    fun `an empty profile leaves the field empty`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel(profileStore = FakeUserProfileStore())
        runCurrent()

        assertEquals("", viewModel.state.value.request.userName)
        assertFalse(viewModel.state.value.canSubmit)
    }

    @Test
    fun `reasons appear only after the first attempt`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel(profileStore = FakeUserProfileStore())
        runCurrent()

        assertFalse(viewModel.state.value.validationShown)

        viewModel.onEvent(QueueEvent.SubmitClicked)
        runCurrent()

        assertTrue(viewModel.state.value.validationShown)
        assertEquals(
            listOf(WalkInRequestError.NameRequired),
            viewModel.state.value.errors,
        )
        // Незаполненная форма в репозиторий не уходит.
        assertTrue(repository.taken.isEmpty())
    }

    @Test
    fun `a taken ticket replaces the form`() = runTest(mainDispatcherRule.dispatcher) {
        repository.takeResult = ApiResult.Success(
            walkInTicket(status = WalkInStatus.Accepted, queuePosition = 3, receivedAt = NOW),
        )
        val viewModel = viewModel()
        runCurrent()

        viewModel.onEvent(QueueEvent.ServiceChanged("Soch olish"))
        viewModel.onEvent(QueueEvent.SubmitClicked)
        runCurrent()

        val state = viewModel.state.value
        assertEquals(WalkInStatus.Accepted, state.ticket?.status)
        assertEquals(3, state.ticket?.queuePosition)
        assertFalse(state.isSubmitting)
        // Только что с сервера — числа очереди свежие.
        assertTrue(state.queueInfoIsCurrent)
        assertEquals(
            "Soch olish" to "Barber House",
            repository.taken.single().let { it.first.serviceName to it.second },
        )
    }

    @Test
    fun `a refused request keeps what was typed`() = runTest(mainDispatcherRule.dispatcher) {
        repository.takeResult = ApiResult.Failure(ApiError.Business("PLACE_CLOSED"))
        val viewModel = viewModel()
        runCurrent()

        viewModel.onEvent(QueueEvent.ServiceChanged("Soch olish"))
        viewModel.onEvent(QueueEvent.SubmitClicked)
        runCurrent()

        val state = viewModel.state.value
        assertEquals(ApiError.Business("PLACE_CLOSED"), state.submitFailure?.error)
        assertEquals("Soch olish", state.request.serviceName)
        assertNull(state.ticket)
    }

    @Test
    fun `editing the form clears the previous refusal`() = runTest(mainDispatcherRule.dispatcher) {
        repository.takeResult = ApiResult.Failure(ApiError.NoConnection)
        val viewModel = viewModel()
        runCurrent()
        viewModel.onEvent(QueueEvent.SubmitClicked)
        runCurrent()

        viewModel.onEvent(QueueEvent.NameChanged("Ali"))

        // Сообщение об отказе поверх изменённой формы относилось бы уже к
        // другому запросу.
        assertNull(viewModel.state.value.submitFailure)
    }

    @Test
    fun `a second tap does not take a second ticket`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        runCurrent()

        viewModel.onEvent(QueueEvent.SubmitClicked)
        viewModel.onEvent(QueueEvent.SubmitClicked)
        runCurrent()

        assertEquals(1, repository.taken.size)
    }

    @Test
    fun `an already taken ticket is shown instead of the form`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.active = walkInTicket(
                status = WalkInStatus.Waiting,
                queuePosition = 2,
                receivedAt = NOW,
            )
            val viewModel = viewModel()
            runCurrent()

            assertEquals("t-1", viewModel.state.value.ticket?.id)
            assertTrue(viewModel.state.value.canCancel)
        }

    @Test
    fun `stale queue numbers are not passed off as current`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.active = walkInTicket(
                status = WalkInStatus.Waiting,
                queuePosition = 2,
                receivedAt = NOW - Duration.ofMinutes(30),
            )
            val viewModel = viewModel()
            runCurrent()

            // Позиция получасовой давности — не текущая: очередь двигают чужие
            // отмены, а перечитать её нечем.
            assertFalse(viewModel.state.value.queueInfoIsCurrent)
            assertEquals(2, viewModel.state.value.ticket?.queuePosition)
        }

    @Test
    fun `returning to the screen recounts freshness without a request`() =
        runTest(mainDispatcherRule.dispatcher) {
            val clock = MovableClock(NOW)
            repository.active = walkInTicket(
                status = WalkInStatus.Waiting,
                queuePosition = 2,
                receivedAt = NOW,
            )
            val viewModel = viewModel(clock = clock)
            runCurrent()
            assertTrue(viewModel.state.value.queueInfoIsCurrent)

            clock.now = NOW + Duration.ofMinutes(5)
            viewModel.onEvent(QueueEvent.ScreenResumed)
            runCurrent()

            assertFalse(viewModel.state.value.queueInfoIsCurrent)
            // Опроса статуса нет и быть не может: читать талон у бэкенда нечем.
            assertTrue(repository.taken.isEmpty())
            assertTrue(repository.cancelled.isEmpty())
        }

    @Test
    fun `cancelling asks for confirmation first`() = runTest(mainDispatcherRule.dispatcher) {
        repository.active = walkInTicket(status = WalkInStatus.Waiting)
        val viewModel = viewModel()
        runCurrent()

        viewModel.onEvent(QueueEvent.CancelClicked)
        runCurrent()
        assertTrue(viewModel.state.value.cancelConfirmVisible)
        assertTrue(repository.cancelled.isEmpty())

        viewModel.onEvent(QueueEvent.CancelDismissed)
        assertFalse(viewModel.state.value.cancelConfirmVisible)
        assertTrue(repository.cancelled.isEmpty())
    }

    @Test
    fun `a confirmed cancel leaves the ticket on screen with its new state`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.active = walkInTicket(status = WalkInStatus.Waiting, queuePosition = 2)
            val viewModel = viewModel()
            runCurrent()

            viewModel.onEvent(QueueEvent.CancelClicked)
            viewModel.onEvent(QueueEvent.CancelConfirmed)
            runCurrent()

            val state = viewModel.state.value
            // Пустая форма читалась бы как «записи и не было».
            assertEquals(WalkInStatus.Cancelled, state.ticket?.status)
            assertFalse(state.canCancel)
            assertFalse(state.isCancelling)
            assertFalse(state.queueInfoIsCurrent)
            assertEquals("t-1", repository.cancelled.single().id)
        }

    @Test
    fun `a refused cancel keeps the ticket and shows the server text`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.active = walkInTicket(status = WalkInStatus.Waiting)
            repository.cancelResult = ApiResult.Failure(ApiError.Business("WALKIN_ALREADY_STARTED"))
            val viewModel = viewModel()
            runCurrent()

            viewModel.onEvent(QueueEvent.CancelClicked)
            viewModel.onEvent(QueueEvent.CancelConfirmed)
            runCurrent()

            val state = viewModel.state.value
            assertEquals(
                ApiError.Business("WALKIN_ALREADY_STARTED"),
                state.cancelFailure?.error,
            )
            assertEquals(WalkInStatus.Waiting, state.ticket?.status)
        }

    @Test
    fun `a ticket in the chair cannot be cancelled from the app`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.active = walkInTicket(status = WalkInStatus.InChair)
            val viewModel = viewModel()
            runCurrent()

            assertFalse(viewModel.state.value.canCancel)

            viewModel.onEvent(QueueEvent.CancelConfirmed)
            runCurrent()

            assertTrue(repository.cancelled.isEmpty())
        }

    @Test
    fun `the notifications centre is the way to the master's answer`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel()
            runCurrent()
            val effects = mutableListOf<QueueEffect>()
            val job = launch { effects += viewModel.effects.first() }

            viewModel.onEvent(QueueEvent.NotificationsClicked)
            runCurrent()
            job.join()

            assertEquals(listOf(QueueEffect.OpenNotifications), effects)
        }

    private fun viewModel(
        profileStore: FakeUserProfileStore = this.profileStore,
        clock: Clock = Clock.fixed(NOW, ZoneOffset.UTC),
    ) = QueueViewModel(
        repository = repository,
        profileStore = profileStore,
        clock = clock,
        savedStateHandle = SavedStateHandle(
            mapOf("placeId" to "p-1", "placeName" to "Barber House"),
        ),
    )

    /** Часы, которые можно подвинуть: свежесть чисел очереди зависит от них. */
    private class MovableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-04T09:00:00Z")
    }
}
