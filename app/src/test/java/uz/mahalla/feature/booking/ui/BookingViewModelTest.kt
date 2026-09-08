package uz.mahalla.feature.booking.ui

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
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.booking.domain.Appointment
import uz.mahalla.feature.booking.domain.AppointmentStatus
import uz.mahalla.feature.booking.domain.BarberService
import uz.mahalla.testutil.FakeBookingRepository
import uz.mahalla.testutil.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Экран записи (issue #97): услуга → день → слот → подтверждение.
 *
 * Под Robolectric, потому что `SavedStateHandle.toRoute()` разбирает
 * типизированный маршрут через настоящий `Bundle` — на голой JVM аргументы
 * читаются как `null`, причём молча.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BookingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeBookingRepository()

    @Test
    fun `the day is chosen from the start and it is today in Tashkent`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel()
            runCurrent()

            val state = viewModel.state.value
            assertEquals("Barber House", state.placeName)
            // Календарь без выбранного дня не отвечает, чьи слоты ниже.
            assertEquals(TODAY, state.selectedDate)
            assertEquals(TODAY, state.dates.first())
        }

    @Test
    fun `a single service selects itself and asks for its slots`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.servicesResult = ApiResult.Success(listOf(service("s-1")))
            repository.defaultSlots = ApiResult.Success(listOf(LocalTime.of(14, 0)))

            val viewModel = viewModel()
            runCurrent()

            val state = viewModel.state.value
            // Заставлять нажимать на список из одной строки незачем.
            assertEquals("s-1", state.selectedServiceId)
            assertEquals(listOf("s-1" to TODAY), repository.requestedSlots)
            assertEquals(
                listOf(LocalTime.of(14, 0)),
                (state.slots as ScreenState.Content).data,
            )
        }

    @Test
    fun `several services wait for a choice and slots are not asked for yet`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.servicesResult =
                ApiResult.Success(listOf(service("s-1"), service("s-2")))

            val viewModel = viewModel()
            runCurrent()

            assertNull(viewModel.state.value.selectedServiceId)
            assertTrue(repository.requestedSlots.isEmpty())
            assertFalse(viewModel.state.value.canBook)
        }

    @Test
    fun `an empty answer is an empty state, not an error`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel()
            runCurrent()

            // Заведение просто не завело услуг — повторять нечего.
            assertTrue(viewModel.state.value.services is ScreenState.Empty)
        }

    @Test
    fun `changing the day drops the chosen slot and asks the server again`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.servicesResult = ApiResult.Success(listOf(service("s-1")))
            repository.defaultSlots = ApiResult.Success(listOf(LocalTime.of(14, 0)))
            val viewModel = viewModel()
            runCurrent()
            viewModel.onEvent(BookingEvent.TimeSelected(LocalTime.of(14, 0)))

            viewModel.onEvent(BookingEvent.DateSelected(TODAY.plusDays(1)))
            runCurrent()

            // `10:00` со вчерашнего дня записало бы человека не туда.
            assertNull(viewModel.state.value.selectedTime)
            assertEquals(
                listOf("s-1" to TODAY, "s-1" to TODAY.plusDays(1)),
                repository.requestedSlots,
            )
        }

    @Test
    fun `changing the service drops the slot too`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.servicesResult =
                ApiResult.Success(listOf(service("s-1"), service("s-2")))
            repository.defaultSlots = ApiResult.Success(listOf(LocalTime.of(14, 0)))
            val viewModel = viewModel()
            runCurrent()
            viewModel.onEvent(BookingEvent.ServiceSelected("s-1"))
            runCurrent()
            viewModel.onEvent(BookingEvent.TimeSelected(LocalTime.of(14, 0)))

            viewModel.onEvent(BookingEvent.ServiceSelected("s-2"))
            runCurrent()

            assertNull(viewModel.state.value.selectedTime)
            assertEquals("s-2", viewModel.state.value.selectedServiceId)
        }

    @Test
    fun `the same service twice does not ask for slots twice`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.servicesResult = ApiResult.Success(listOf(service("s-1")))
            val viewModel = viewModel()
            runCurrent()

            viewModel.onEvent(BookingEvent.ServiceSelected("s-1"))
            runCurrent()

            assertEquals(1, repository.requestedSlots.size)
        }

    @Test
    fun `booking sends exactly what was chosen`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.servicesResult = ApiResult.Success(listOf(service("s-1")))
            repository.defaultSlots =
                ApiResult.Success(listOf(LocalTime.of(14, 0), LocalTime.of(14, 30)))
            val viewModel = viewModel()
            runCurrent()
            viewModel.onEvent(BookingEvent.DateSelected(TODAY.plusDays(2)))
            runCurrent()
            viewModel.onEvent(BookingEvent.TimeSelected(LocalTime.of(14, 30)))
            assertTrue(viewModel.state.value.canBook)

            viewModel.onEvent(BookingEvent.BookClicked)
            runCurrent()

            assertEquals(
                listOf(
                    FakeBookingRepository.BookedRequest(
                        placeId = "p-1",
                        serviceId = "s-1",
                        date = TODAY.plusDays(2),
                        time = LocalTime.of(14, 30),
                    ),
                ),
                repository.booked,
            )
        }

    @Test
    fun `an incomplete choice is not sent anywhere`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.servicesResult = ApiResult.Success(listOf(service("s-1")))
            val viewModel = viewModel()
            runCurrent()

            // Слот не выбран: кнопка выключена, и событие ничего не делает.
            assertFalse(viewModel.state.value.canBook)
            viewModel.onEvent(BookingEvent.BookClicked)
            runCurrent()

            assertTrue(repository.booked.isEmpty())
        }

    @Test
    fun `the confirmation stays on the screen instead of a silent jump`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.servicesResult = ApiResult.Success(listOf(service("s-1")))
            repository.defaultSlots = ApiResult.Success(listOf(LocalTime.of(14, 0)))
            // Сервер не назвал ни услугу, ни время — подтверждение обязано
            // сказать, на что записались.
            repository.bookResult = ApiResult.Success(
                Appointment(id = "a-9", status = AppointmentStatus.Pending),
            )
            val viewModel = viewModel()
            runCurrent()
            viewModel.onEvent(BookingEvent.TimeSelected(LocalTime.of(14, 0)))

            viewModel.onEvent(BookingEvent.BookClicked)
            runCurrent()

            val booked = viewModel.state.value.booked
            assertEquals("a-9", booked?.id)
            assertEquals("Soch olish", booked?.serviceName)
            assertEquals(TODAY, booked?.date)
            assertEquals(LocalTime.of(14, 0), booked?.startTime)
            // Второй раз ту же запись не создать.
            assertFalse(viewModel.state.value.canBook)
        }

    @Test
    fun `a refusal keeps the choice and shows the text of the server`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.servicesResult = ApiResult.Success(listOf(service("s-1")))
            repository.defaultSlots = ApiResult.Success(listOf(LocalTime.of(14, 0)))
            repository.bookResult = ApiResult.Failure(ApiError.Business("SLOT_TAKEN"))
            val viewModel = viewModel()
            runCurrent()
            viewModel.onEvent(BookingEvent.TimeSelected(LocalTime.of(14, 0)))

            viewModel.onEvent(BookingEvent.BookClicked)
            runCurrent()

            val state = viewModel.state.value
            assertEquals(ApiError.Business("SLOT_TAKEN"), state.bookFailure?.error)
            // Терять выбор из-за отказа незачем — попробовать ещё раз можно
            // сразу.
            assertEquals(LocalTime.of(14, 0), state.selectedTime)
            assertNull(state.booked)
            assertTrue(state.canBook)
        }

    @Test
    fun `changing the slot clears the previous refusal`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.servicesResult = ApiResult.Success(listOf(service("s-1")))
            repository.defaultSlots =
                ApiResult.Success(listOf(LocalTime.of(14, 0), LocalTime.of(15, 0)))
            repository.bookResult = ApiResult.Failure(ApiError.Business("SLOT_TAKEN"))
            val viewModel = viewModel()
            runCurrent()
            viewModel.onEvent(BookingEvent.TimeSelected(LocalTime.of(14, 0)))
            viewModel.onEvent(BookingEvent.BookClicked)
            runCurrent()

            viewModel.onEvent(BookingEvent.TimeSelected(LocalTime.of(15, 0)))

            // Сообщение относилось к занятому слоту, а выбран уже другой.
            assertNull(viewModel.state.value.bookFailure)
        }

    @Test
    fun `refused slots can be retried without touching the service`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.servicesResult = ApiResult.Success(listOf(service("s-1")))
            repository.defaultSlots = ApiResult.Failure(ApiError.NoConnection)
            val viewModel = viewModel()
            runCurrent()
            assertTrue(viewModel.state.value.slots is ScreenState.Error)

            repository.defaultSlots = ApiResult.Success(listOf(LocalTime.of(14, 0)))
            viewModel.onEvent(BookingEvent.SlotsRetry)
            runCurrent()

            assertEquals(
                listOf(LocalTime.of(14, 0)),
                (viewModel.state.value.slots as ScreenState.Content).data,
            )
            assertEquals("s-1", viewModel.state.value.selectedServiceId)
        }

    @Test
    fun `my appointments are opened from the confirmation`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel()
            val effects = mutableListOf<BookingEffect>()
            val job = launch { effects += viewModel.effects.first() }
            runCurrent()

            viewModel.onEvent(BookingEvent.MyAppointmentsClicked)
            runCurrent()
            job.join()

            assertEquals(listOf(BookingEffect.OpenMyAppointments), effects)
        }

    private fun service(id: String) = BarberService(
        id = id,
        title = "Soch olish",
        priceSum = 60_000,
        durationMinutes = 40,
    )

    private fun viewModel() = BookingViewModel(
        repository = repository,
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
        savedStateHandle = SavedStateHandle(
            mapOf("placeId" to "p-1", "placeName" to "Barber House"),
        ),
    )

    private companion object {
        /** 09:00 UTC = 14:00 в Ташкенте, 4 сентября. */
        val NOW: Instant = Instant.parse("2026-09-04T09:00:00Z")
        val TODAY: LocalDate = LocalDate.of(2026, 9, 4)
    }
}
