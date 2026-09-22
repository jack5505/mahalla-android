package uz.mahalla.feature.business.ui.journal

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.booking.domain.Appointment
import uz.mahalla.feature.booking.domain.AppointmentPage
import uz.mahalla.feature.booking.domain.AppointmentStatus
import uz.mahalla.feature.booking.domain.AppointmentVertical
import uz.mahalla.feature.hospital.domain.Doctor
import uz.mahalla.navigation.BusinessArgs
import uz.mahalla.testutil.FakeBusinessRepository
import uz.mahalla.testutil.JournalRequest
import uz.mahalla.testutil.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Журнал записей на день (issue #289) — барбершоп и клиника.
 *
 * Главное здесь — то же, что у ленты заказов: переход статуса проверяется
 * **до** запроса, а смена дня/фильтра — новый запрос, не локальная фильтрация.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BusinessJournalViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun `the first page is loaded for today, without filters`() = runTest {
        val repository = FakeBusinessRepository()
        repository.defaultJournalPage = page(listOf(appointment("a-1", AppointmentStatus.Pending)))

        val state = viewModel(repository).state.value

        assertEquals(1, (state.appointments as ScreenState.Content).data.size)
        assertEquals(
            JournalRequest(AppointmentVertical.Barber, TODAY, null, null, 0),
            repository.journalRequests.single(),
        )
    }

    @Test
    fun `an empty answer is an empty state, not an error`() = runTest {
        val state = viewModel(FakeBusinessRepository()).state.value

        assertTrue(state.appointments is ScreenState.Empty)
        assertFalse(state.hasMore)
    }

    @Test
    fun `a foreign place id surfaces the forbidden error`() = runTest {
        val repository = FakeBusinessRepository()
        repository.defaultJournalPage = ApiResult.Failure(ApiFailure(ApiError.Forbidden))

        val state = viewModel(repository).state.value

        assertEquals(ApiError.Forbidden, (state.appointments as ScreenState.Error).failure.error)
    }

    @Test
    fun `shifting the day asks the server with the new date`() = runTest {
        val repository = FakeBusinessRepository()
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessJournalEvent.DateShifted(1L))

        assertEquals(TODAY.plusDays(1), viewModel.state.value.date)
        assertEquals(
            listOf(TODAY, TODAY.plusDays(1)),
            repository.journalRequests.map(JournalRequest::date),
        )
    }

    @Test
    fun `selecting a status tab asks the server with that status`() = runTest {
        val repository = FakeBusinessRepository()
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessJournalEvent.StatusFilterSelected(AppointmentStatus.Confirmed))

        assertEquals(
            listOf(null, AppointmentStatus.Confirmed),
            repository.journalRequests.map(JournalRequest::status),
        )
    }

    /** У барбершопа фильтра по врачу нет вовсе — своих мастеров панель не разводит. */
    @Test
    fun `the barber journal never loads doctors`() = runTest {
        val repository = FakeBusinessRepository()

        val state = viewModel(repository, vertical = AppointmentVertical.Barber).state.value

        assertFalse(state.isDoctorFilterVisible)
        assertTrue(state.doctors.isEmpty())
    }

    /** Клиника грузит список врачей на старте — фильтр журнала (issue #289). */
    @Test
    fun `the clinic journal loads doctors for the filter`() = runTest {
        val repository = FakeBusinessRepository()
        repository.doctorsResult = ApiResult.Success(listOf(Doctor(id = "d-1", name = "Dr. Karimova")))

        val state = viewModel(repository, vertical = AppointmentVertical.Doctor).state.value

        assertTrue(state.isDoctorFilterVisible)
        assertEquals("Dr. Karimova", state.doctors.single().name)
    }

    @Test
    fun `selecting a doctor asks the server with that doctor`() = runTest {
        val repository = FakeBusinessRepository()
        val viewModel = viewModel(repository, vertical = AppointmentVertical.Doctor)

        viewModel.onEvent(BusinessJournalEvent.DoctorFilterSelected("d-1"))

        assertEquals(
            listOf(null, "d-1"),
            repository.journalRequests.map(JournalRequest::doctorId),
        )
    }

    @Test
    fun `confirming a pending appointment moves it to confirmed`() = runTest {
        val repository = FakeBusinessRepository()
        repository.defaultJournalPage = page(listOf(appointment("a-1", AppointmentStatus.Pending)))
        repository.updateAppointmentResult =
            ApiResult.Success(appointment("a-1", AppointmentStatus.Confirmed))
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessJournalEvent.StatusSelected("a-1", AppointmentStatus.Confirmed))

        assertEquals(listOf("a-1" to AppointmentStatus.Confirmed), repository.appointmentStatusUpdates)
        assertEquals(listOf(AppointmentVertical.Barber), repository.appointmentStatusUpdateVerticals)
        val appointments = (viewModel.state.value.appointments as ScreenState.Content).data
        assertEquals(AppointmentStatus.Confirmed, appointments.single().status)
        assertNull(viewModel.state.value.pendingAppointmentId)
    }

    @Test
    fun `a forbidden transition does not reach the server`() = runTest {
        val repository = FakeBusinessRepository()
        repository.defaultJournalPage = page(listOf(appointment("a-1", AppointmentStatus.Pending)))
        val viewModel = viewModel(repository)

        // Через шаг: `PENDING` → `COMPLETED` бэкенд не разрешал бы.
        viewModel.onEvent(BusinessJournalEvent.StatusSelected("a-1", AppointmentStatus.Completed))

        assertTrue(repository.appointmentStatusUpdates.isEmpty())
    }

    /** У клиники нет «не пришёл» — `HospitalAppointmentResponse` такого статуса не знает. */
    @Test
    fun `a doctor appointment cannot be marked as a no-show`() = runTest {
        val repository = FakeBusinessRepository()
        repository.defaultJournalPage =
            page(listOf(appointment("a-1", AppointmentStatus.Confirmed)))
        val viewModel = viewModel(repository, vertical = AppointmentVertical.Doctor)

        viewModel.onEvent(BusinessJournalEvent.StatusSelected("a-1", AppointmentStatus.NoShow))

        assertTrue(repository.appointmentStatusUpdates.isEmpty())
    }

    @Test
    fun `a refusal keeps the list and shows the server message`() = runTest {
        val repository = FakeBusinessRepository()
        repository.defaultJournalPage = page(listOf(appointment("a-1", AppointmentStatus.Pending)))
        repository.updateAppointmentResult = ApiResult.Failure(ApiFailure(ApiError.Forbidden))
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessJournalEvent.StatusSelected("a-1", AppointmentStatus.Confirmed))

        val state = viewModel.state.value
        assertEquals(ApiError.Forbidden, state.actionFailure?.error)
        assertEquals(
            AppointmentStatus.Pending,
            (state.appointments as ScreenState.Content).data.single().status,
        )
        assertNull(state.pendingAppointmentId)
    }

    @Test
    fun `the next page is appended, not replaced`() = runTest {
        val repository = FakeBusinessRepository()
        repository.journalPages[TODAY to 0] =
            page(listOf(appointment("a-1", AppointmentStatus.Pending)), hasMore = true)
        repository.journalPages[TODAY to 1] = page(listOf(appointment("a-2", AppointmentStatus.Confirmed)))
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessJournalEvent.LoadMore)

        val appointments = (viewModel.state.value.appointments as ScreenState.Content).data
        assertEquals(listOf("a-1", "a-2"), appointments.map(Appointment::id))
        assertFalse(viewModel.state.value.hasMore)
    }

    @Test
    fun `returning to the screen re-reads the first page`() = runTest {
        val repository = FakeBusinessRepository()
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessJournalEvent.ScreenResumed)

        assertEquals(listOf(0, 0), repository.journalRequests.map(JournalRequest::page))
    }

    private fun viewModel(
        repository: FakeBusinessRepository,
        vertical: AppointmentVertical = AppointmentVertical.Barber,
    ) = BusinessJournalViewModel(
        repository = repository,
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
        savedStateHandle = SavedStateHandle(
            mapOf(
                BusinessArgs.PLACE_ID to FakeBusinessRepository.PLACE_ID,
                BusinessArgs.PLACE_NAME to "Sartaroshxona Alex",
                BusinessArgs.VERTICAL to vertical.name,
            ),
        ),
    )

    private fun page(items: List<Appointment>, hasMore: Boolean = false) =
        ApiResult.Success(AppointmentPage(items = items, hasMore = hasMore))

    private fun appointment(id: String, status: AppointmentStatus) = Appointment(
        id = id,
        serviceName = "Soch olish",
        status = status,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-22T09:00:00Z")
        val TODAY: LocalDate = LocalDate.of(2026, 9, 22)
    }
}
