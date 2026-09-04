package uz.mahalla.feature.gaming.ui.zones

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import uz.mahalla.feature.gaming.domain.GamingBookingDraft
import uz.mahalla.feature.gaming.domain.GamingBookingError
import uz.mahalla.testutil.FakeGamingRepository
import uz.mahalla.testutil.MainDispatcherRule
import uz.mahalla.testutil.gamingZone
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Экран игровых зон (issue #98): список, шторка брони и подтверждение.
 *
 * Под Robolectric, потому что `SavedStateHandle.toRoute()` разбирает
 * типизированный маршрут через настоящий `Bundle` — на голой JVM аргументы
 * читаются как `null`, причём молча.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class GamingZonesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeGamingRepository()

    @Test
    fun `zones of the place are loaded on open`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        runCurrent()

        val state = viewModel.state.value
        assertEquals("Cyber Arena", state.placeName)
        assertEquals(listOf("z-1"), (state.zones as ScreenState.Content).data.map { it.id })
    }

    @Test
    fun `an empty answer is empty, not an error`() = runTest(mainDispatcherRule.dispatcher) {
        // Каталог стенда пуст, и это ответ сервера, а не поломка экрана.
        repository.zonesResult = ApiResult.Success(emptyList())

        val viewModel = viewModel()
        runCurrent()

        assertEquals(ScreenState.Empty, viewModel.state.value.zones)
    }

    @Test
    fun `a refusal shows the reason and retry loads again`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.zonesResult = ApiResult.Failure(ApiError.NoConnection)

            val viewModel = viewModel()
            runCurrent()
            assertTrue(viewModel.state.value.zones is ScreenState.Error)

            repository.zonesResult = ApiResult.Success(listOf(gamingZone()))
            viewModel.onEvent(GamingZonesEvent.Retry)
            runCurrent()

            assertTrue(viewModel.state.value.zones is ScreenState.Content)
        }

    @Test
    fun `the sheet opens with the nearest slot preselected`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel()
            runCurrent()

            viewModel.onEvent(GamingZonesEvent.ZoneClicked("z-1"))

            val state = viewModel.state.value
            assertEquals("z-1", state.selectedZone?.id)
            assertTrue(state.slots.isNotEmpty())
            // Первый слот подставлен: пустая шторка требовала бы лишнего
            // действия там, где выбор очевиден.
            assertEquals(state.slots.first(), state.draft?.startTime)
            assertEquals(GamingBookingDraft.DEFAULT_HOURS, state.draft?.durationHours)
            assertTrue(state.canBook)
        }

    @Test
    fun `a closed zone does not open the sheet`() = runTest(mainDispatcherRule.dispatcher) {
        // Такая карточка и не кликабельна; проверка — на случай, если событие
        // всё-таки придёт.
        repository.zonesResult = ApiResult.Success(listOf(gamingZone(isAvailable = false)))

        val viewModel = viewModel()
        runCurrent()
        viewModel.onEvent(GamingZonesEvent.ZoneClicked("z-1"))

        assertNull(viewModel.state.value.selectedZone)
    }

    @Test
    fun `an unknown zone is ignored`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        runCurrent()

        viewModel.onEvent(GamingZonesEvent.ZoneClicked("z-404"))

        assertNull(viewModel.state.value.selectedZone)
    }

    @Test
    fun `the total follows the hours`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        runCurrent()
        viewModel.onEvent(GamingZonesEvent.ZoneClicked("z-1"))

        viewModel.onEvent(GamingZonesEvent.DurationChanged(3))

        assertEquals(3, viewModel.state.value.draft?.durationHours)
        assertEquals(90_000L, viewModel.state.value.totalPrice)
    }

    @Test
    fun `the hours are clamped instead of falling out of the range`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel()
            runCurrent()
            viewModel.onEvent(GamingZonesEvent.ZoneClicked("z-1"))

            viewModel.onEvent(GamingZonesEvent.DurationChanged(99))
            assertEquals(GamingBookingDraft.MAX_HOURS, viewModel.state.value.draft?.durationHours)

            viewModel.onEvent(GamingZonesEvent.DurationChanged(0))
            assertEquals(GamingBookingDraft.MIN_HOURS, viewModel.state.value.draft?.durationHours)
        }

    @Test
    fun `booking sends what was chosen and leaves a confirmation`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel()
            runCurrent()
            viewModel.onEvent(GamingZonesEvent.ZoneClicked("z-1"))
            val slot = viewModel.state.value.slots[2]
            viewModel.onEvent(GamingZonesEvent.SlotSelected(slot))
            viewModel.onEvent(GamingZonesEvent.DurationChanged(2))

            viewModel.onEvent(GamingZonesEvent.BookClicked)
            runCurrent()

            val (draft, zoneName) = repository.booked.single()
            assertEquals("z-1", draft.zoneId)
            assertEquals(slot, draft.startTime)
            assertEquals(2, draft.durationHours)
            assertEquals("PlayStation 5", zoneName)

            val state = viewModel.state.value
            // Шторка закрыта, подтверждение осталось: бронь состоялась, и об
            // этом надо сказать словами, а не пустотой.
            assertNull(state.selectedZone)
            assertNull(state.draft)
            assertNotNull(state.confirmed)
            assertFalse(state.isBooking)
        }

    @Test
    fun `a slot that expired while the sheet was open is caught before the network`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel()
            runCurrent()
            viewModel.onEvent(GamingZonesEvent.ZoneClicked("z-1"))
            viewModel.onEvent(GamingZonesEvent.SlotSelected(NOW.minusSeconds(600)))

            viewModel.onEvent(GamingZonesEvent.BookClicked)
            runCurrent()

            val state = viewModel.state.value
            assertTrue(state.errors.contains(GamingBookingError.TimeTooSoon))
            assertTrue(state.validationShown)
            // Запрос не ушёл, шторка осталась открытой с выбором человека.
            assertTrue(repository.booked.isEmpty())
            assertNotNull(state.selectedZone)
        }

    @Test
    fun `a refusal of the booking keeps the sheet and the choice`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.bookResult = ApiResult.Failure(ApiError.Business("ZONE_BUSY"))

            val viewModel = viewModel()
            runCurrent()
            viewModel.onEvent(GamingZonesEvent.ZoneClicked("z-1"))
            viewModel.onEvent(GamingZonesEvent.DurationChanged(4))
            viewModel.onEvent(GamingZonesEvent.BookClicked)
            runCurrent()

            val state = viewModel.state.value
            assertEquals(ApiError.Business("ZONE_BUSY"), state.bookingFailure?.error)
            assertNotNull(state.selectedZone)
            assertEquals(4, state.draft?.durationHours)
            assertNull(state.confirmed)
        }

    @Test
    fun `changing the choice clears the previous refusal`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.bookResult = ApiResult.Failure(ApiError.NoConnection)

            val viewModel = viewModel()
            runCurrent()
            viewModel.onEvent(GamingZonesEvent.ZoneClicked("z-1"))
            viewModel.onEvent(GamingZonesEvent.BookClicked)
            runCurrent()
            assertNotNull(viewModel.state.value.bookingFailure)

            viewModel.onEvent(GamingZonesEvent.DurationChanged(2))

            // Сообщение относилось к прошлой попытке — другой бронe.
            assertNull(viewModel.state.value.bookingFailure)
        }

    @Test
    fun `the second tap does not book twice`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        runCurrent()
        viewModel.onEvent(GamingZonesEvent.ZoneClicked("z-1"))

        viewModel.onEvent(GamingZonesEvent.BookClicked)
        viewModel.onEvent(GamingZonesEvent.BookClicked)
        runCurrent()

        assertEquals(1, repository.booked.size)
    }

    @Test
    fun `returning to the screen refreshes the zones`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        runCurrent()
        repository.zonesResult = ApiResult.Success(
            listOf(gamingZone(id = "z-2", name = "VR")),
        )

        viewModel.onEvent(GamingZonesEvent.ScreenResumed)
        runCurrent()

        assertEquals(
            listOf("z-2"),
            (viewModel.state.value.zones as ScreenState.Content).data.map { it.id },
        )
    }

    @Test
    fun `my bookings are reachable from the screen`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        runCurrent()

        val effects = mutableListOf<GamingZonesEffect>()
        val job = launch { effects += viewModel.effects.first() }
        viewModel.onEvent(GamingZonesEvent.MyBookingsClicked)
        runCurrent()
        job.join()

        assertEquals(listOf(GamingZonesEffect.OpenMyBookings), effects)
    }

    @Test
    fun `the confirmation is dismissed by hand`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        runCurrent()
        viewModel.onEvent(GamingZonesEvent.ZoneClicked("z-1"))
        viewModel.onEvent(GamingZonesEvent.BookClicked)
        runCurrent()
        assertNotNull(viewModel.state.value.confirmed)

        viewModel.onEvent(GamingZonesEvent.ConfirmationDismissed)

        assertNull(viewModel.state.value.confirmed)
    }

    private fun viewModel() = GamingZonesViewModel(
        repository = repository,
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
        savedStateHandle = SavedStateHandle(
            mapOf("placeId" to "p-1", "placeName" to "Cyber Arena"),
        ),
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-04T12:00:00Z")
    }
}
