package uz.mahalla.feature.hospital.ui

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
import uz.mahalla.feature.hospital.domain.Doctor
import uz.mahalla.feature.hospital.domain.DoctorAppointmentDraft
import uz.mahalla.feature.hospital.domain.DoctorSchedule
import uz.mahalla.testutil.FakeHospitalRepository
import uz.mahalla.testutil.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Экран записи к врачу (issue #99): врач → день → время → жалоба →
 * подтверждение.
 *
 * Под Robolectric, потому что `SavedStateHandle.toRoute()` разбирает
 * типизированный маршрут через настоящий `Bundle` — на голой JVM аргументы
 * читаются как `null`, причём молча.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DoctorBookingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeHospitalRepository()

    @Test
    fun `the day is chosen from the start and it is today in Tashkent`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel()
            runCurrent()

            val state = viewModel.state.value
            assertEquals("Shifo klinikasi", state.placeName)
            assertEquals(TODAY, state.draft.date)
            assertEquals(TODAY, state.dates.first())
            assertEquals(listOf(PLACE), repository.requestedDoctors)
        }

    /**
     * Сетка на сегодня начинается с текущего часа: 09:00 UTC — это 14:00 в
     * Ташкенте, и предлагать утренний приём в обед нельзя.
     */
    @Test
    fun `today offers only the time that has not passed`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel()
            runCurrent()

            val times = viewModel.state.value.times
            assertEquals(LocalTime.of(14, 0), times.first())
            assertEquals(DoctorSchedule.LAST_START, times.last())
        }

    @Test
    fun `a single doctor selects itself`() = runTest(mainDispatcherRule.dispatcher) {
        repository.doctorsResult = ApiResult.Success(listOf(doctor("d-1")))

        val viewModel = viewModel()
        runCurrent()

        // Заставлять нажимать на список из одной строки незачем.
        assertEquals("d-1", viewModel.state.value.draft.doctorId)
    }

    @Test
    fun `several doctors are not chosen for the person`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.doctorsResult = ApiResult.Success(listOf(doctor("d-1"), doctor("d-2")))

            val viewModel = viewModel()
            runCurrent()

            assertNull(viewModel.state.value.draft.doctorId)
            assertFalse(viewModel.state.value.canBook)
        }

    @Test
    fun `an empty doctor list is an empty state, not an error`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel()
            runCurrent()

            assertTrue(viewModel.state.value.doctors is ScreenState.Empty)
        }

    @Test
    fun `a refusal of the doctor list is shown with the server text`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.doctorsResult = ApiResult.Failure(ApiError.NoConnection)

            val viewModel = viewModel()
            runCurrent()

            assertEquals(
                ApiError.NoConnection,
                (viewModel.state.value.doctors as ScreenState.Error).error,
            )

            repository.doctorsResult = ApiResult.Success(listOf(doctor("d-1")))
            viewModel.onEvent(DoctorBookingEvent.DoctorsRetry)
            runCurrent()

            assertTrue(viewModel.state.value.doctors is ScreenState.Content)
        }

    /**
     * `10:00` от сегодняшнего дня на завтрашнем — уже другое время, а на
     * сегодняшнем его может не быть вовсе: выбор обязан сброситься.
     */
    @Test
    fun `changing the day resets the time and recounts the grid`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel()
            runCurrent()
            viewModel.onEvent(DoctorBookingEvent.TimeSelected(LocalTime.of(15, 0)))

            viewModel.onEvent(DoctorBookingEvent.DateSelected(TOMORROW))

            val state = viewModel.state.value
            assertNull(state.draft.time)
            // Завтра приём начинается с утра.
            assertEquals(DoctorSchedule.OPENS_AT, state.times.first())
        }

    @Test
    fun `booking sends the whole draft, complaint included`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.doctorsResult = ApiResult.Success(listOf(doctor("d-1")))
            val viewModel = viewModel()
            runCurrent()

            viewModel.onEvent(DoctorBookingEvent.TimeSelected(LocalTime.of(15, 0)))
            viewModel.onEvent(DoctorBookingEvent.ComplaintChanged("tomoq og'riyapti"))
            assertTrue(viewModel.state.value.canBook)

            viewModel.onEvent(DoctorBookingEvent.BookClicked)
            runCurrent()

            assertEquals(
                listOf(
                    DoctorAppointmentDraft(
                        doctorId = "d-1",
                        date = TODAY,
                        time = LocalTime.of(15, 0),
                        complaint = "tomoq og'riyapti",
                    ),
                ),
                repository.booked,
            )
        }

    /**
     * После успеха экран остаётся на месте с подтверждением: молчаливый переход
     * читается как «ничего не произошло» (issue #49).
     */
    @Test
    fun `a created appointment is shown on the same screen`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.doctorsResult = ApiResult.Success(listOf(doctor("d-1")))
            // Сервер не назвал ни врача, ни время — подставляем выбор человека.
            repository.bookResult = ApiResult.Success(Appointment(id = "a-1"))
            val viewModel = viewModel()
            runCurrent()
            viewModel.onEvent(DoctorBookingEvent.TimeSelected(LocalTime.of(15, 0)))

            viewModel.onEvent(DoctorBookingEvent.BookClicked)
            runCurrent()

            val booked = viewModel.state.value.booked
            assertEquals("Aliyev Bekzod", booked?.serviceName)
            assertEquals(TODAY, booked?.date)
            assertEquals(LocalTime.of(15, 0), booked?.startTime)
            assertFalse(viewModel.state.value.isBooking)
        }

    @Test
    fun `the server name of the appointment wins over the chosen doctor`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.doctorsResult = ApiResult.Success(listOf(doctor("d-1")))
            repository.bookResult = ApiResult.Success(
                Appointment(
                    id = "a-1",
                    serviceName = "Terapevt qabuli",
                    status = AppointmentStatus.Pending,
                ),
            )
            val viewModel = viewModel()
            runCurrent()
            viewModel.onEvent(DoctorBookingEvent.TimeSelected(LocalTime.of(15, 0)))

            viewModel.onEvent(DoctorBookingEvent.BookClicked)
            runCurrent()

            assertEquals("Terapevt qabuli", viewModel.state.value.booked?.serviceName)
        }

    @Test
    fun `a refused booking keeps the choice and the complaint`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.doctorsResult = ApiResult.Success(listOf(doctor("d-1")))
            repository.bookResult = ApiResult.Failure(ApiError.Business("SLOT_TAKEN"))
            val viewModel = viewModel()
            runCurrent()
            viewModel.onEvent(DoctorBookingEvent.TimeSelected(LocalTime.of(15, 0)))
            viewModel.onEvent(DoctorBookingEvent.ComplaintChanged("bosh og'riq"))

            viewModel.onEvent(DoctorBookingEvent.BookClicked)
            runCurrent()

            val state = viewModel.state.value
            assertEquals(ApiError.Business("SLOT_TAKEN"), state.bookFailure?.error)
            assertNull(state.booked)
            assertEquals(LocalTime.of(15, 0), state.draft.time)
            assertEquals("bosh og'riq", state.draft.complaint)

            // Правка снимает прошлый отказ: он был про другой текст.
            viewModel.onEvent(DoctorBookingEvent.ComplaintChanged("bosh og'riq, uch kun"))
            assertNull(viewModel.state.value.bookFailure)
        }

    @Test
    fun `too long complaint blocks the button before any request`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.doctorsResult = ApiResult.Success(listOf(doctor("d-1")))
            val viewModel = viewModel()
            runCurrent()
            viewModel.onEvent(DoctorBookingEvent.TimeSelected(LocalTime.of(15, 0)))

            viewModel.onEvent(
                DoctorBookingEvent.ComplaintChanged(
                    "a".repeat(DoctorAppointmentDraft.MAX_COMPLAINT_LENGTH + 1),
                ),
            )

            assertFalse(viewModel.state.value.canBook)
            viewModel.onEvent(DoctorBookingEvent.BookClicked)
            runCurrent()
            assertTrue(repository.booked.isEmpty())
        }

    /** Второй тап по кнопке не заводит вторую запись. */
    @Test
    fun `a second tap does not book twice`() = runTest(mainDispatcherRule.dispatcher) {
        repository.doctorsResult = ApiResult.Success(listOf(doctor("d-1")))
        val viewModel = viewModel()
        runCurrent()
        viewModel.onEvent(DoctorBookingEvent.TimeSelected(LocalTime.of(15, 0)))

        viewModel.onEvent(DoctorBookingEvent.BookClicked)
        viewModel.onEvent(DoctorBookingEvent.BookClicked)
        runCurrent()

        assertEquals(1, repository.booked.size)
    }

    @Test
    fun `my appointments are opened from the confirmation`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = viewModel()
            runCurrent()
            val effects = mutableListOf<DoctorBookingEffect>()
            val job = launch { effects += viewModel.effects.first() }

            viewModel.onEvent(DoctorBookingEvent.MyAppointmentsClicked)
            runCurrent()
            job.join()

            assertEquals(listOf(DoctorBookingEffect.OpenMyAppointments), effects)
        }

    private fun doctor(id: String) = Doctor(
        id = id,
        name = "Aliyev Bekzod",
        specialty = "Terapevt",
        consultationPriceSum = 90_000,
    )

    private fun viewModel() = DoctorBookingViewModel(
        repository = repository,
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
        savedStateHandle = SavedStateHandle(
            mapOf("placeId" to PLACE, "placeName" to "Shifo klinikasi"),
        ),
    )

    private companion object {
        /** 09:00 UTC = 14:00 в Ташкенте, 4 сентября. */
        val NOW: Instant = Instant.parse("2026-09-04T09:00:00Z")
        val TODAY: LocalDate = LocalDate.of(2026, 9, 4)
        val TOMORROW: LocalDate = LocalDate.of(2026, 9, 5)
        const val PLACE = "p-1"
    }
}
