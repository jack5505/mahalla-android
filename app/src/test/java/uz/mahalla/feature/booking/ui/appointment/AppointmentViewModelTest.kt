package uz.mahalla.feature.booking.ui.appointment

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
import uz.mahalla.feature.booking.domain.Appointment
import uz.mahalla.feature.booking.domain.AppointmentStatus
import uz.mahalla.feature.booking.domain.AppointmentVertical
import uz.mahalla.testutil.FakeBookingRepository
import uz.mahalla.testutil.FakeHospitalRepository
import uz.mahalla.testutil.MainDispatcherRule

/**
 * Карточка записи (issue #183): читается по id при открытии и на
 * pull-to-refresh, экран один на обе вертикали, как и «Мои записи».
 *
 * Под Robolectric по той же причине, что и карточка фильма: `toRoute()`
 * разбирает маршрут настоящим `Bundle`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AppointmentViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val bookingRepository = FakeBookingRepository()
    private val hospitalRepository = FakeHospitalRepository()

    @Test
    fun `barber appointment is read by id from the barber source`() =
        runTest(mainDispatcherRule.dispatcher) {
            bookingRepository.appointmentResult = ApiResult.Success(
                Appointment(id = APPOINTMENT, serviceName = "Soch olish"),
            )

            val viewModel = viewModel(AppointmentVertical.Barber)
            runCurrent()

            val state = viewModel.state.value
            assertEquals(AppointmentVertical.Barber, state.vertical)
            assertEquals("Soch olish", (state.appointment as ScreenState.Content).data.serviceName)
            assertEquals(listOf(APPOINTMENT), bookingRepository.requestedAppointments)
            assertTrue(hospitalRepository.requestedAppointments.isEmpty())
        }

    @Test
    fun `doctor appointment is read from the hospital source`() =
        runTest(mainDispatcherRule.dispatcher) {
            hospitalRepository.appointmentResult = ApiResult.Success(
                Appointment(id = APPOINTMENT, serviceName = "Aliyev Bekzod"),
            )

            val viewModel = viewModel(AppointmentVertical.Doctor)
            runCurrent()

            val state = viewModel.state.value
            assertEquals(AppointmentVertical.Doctor, state.vertical)
            assertEquals("Aliyev Bekzod", (state.appointment as ScreenState.Content).data.serviceName)
            assertEquals(listOf(APPOINTMENT), hospitalRepository.requestedAppointments)
            assertTrue(bookingRepository.requestedAppointments.isEmpty())
        }

    /** Заведение отменило запись, пока человек шёл со списка. */
    @Test
    fun `a missing appointment is an error`() = runTest(mainDispatcherRule.dispatcher) {
        bookingRepository.appointmentResult = ApiResult.Failure(ApiError.NotFound)

        val viewModel = viewModel(AppointmentVertical.Barber)
        runCurrent()

        assertEquals(
            ApiError.NotFound,
            (viewModel.state.value.appointment as ScreenState.Error).error,
        )
    }

    @Test
    fun `pull-to-refresh reads the appointment again`() = runTest(mainDispatcherRule.dispatcher) {
        bookingRepository.appointmentResult = ApiResult.Success(
            Appointment(id = APPOINTMENT, status = AppointmentStatus.Pending),
        )
        val viewModel = viewModel(AppointmentVertical.Barber)
        runCurrent()

        bookingRepository.appointmentResult = ApiResult.Success(
            Appointment(id = APPOINTMENT, status = AppointmentStatus.Confirmed),
        )
        viewModel.onEvent(AppointmentEvent.Refreshed)
        assertTrue(viewModel.state.value.isRefreshing)
        runCurrent()

        assertFalse(viewModel.state.value.isRefreshing)
        val state = viewModel.state.value.appointment as ScreenState.Content
        assertEquals(AppointmentStatus.Confirmed, state.data.status)
        assertEquals(listOf(APPOINTMENT, APPOINTMENT), bookingRepository.requestedAppointments)
    }

    /** Возврат на экран мог застать запись уже подтверждённой или отменённой. */
    @Test
    fun `returning to the screen reloads the appointment`() = runTest(mainDispatcherRule.dispatcher) {
        bookingRepository.appointmentResult = ApiResult.Success(Appointment(id = APPOINTMENT))
        val viewModel = viewModel(AppointmentVertical.Barber)
        runCurrent()

        // Первый resume пропускается — это открытие, а не возврат (issue #145).
        viewModel.onEvent(AppointmentEvent.ScreenResumed)
        viewModel.onEvent(AppointmentEvent.ScreenResumed)
        runCurrent()

        assertEquals(listOf(APPOINTMENT, APPOINTMENT), bookingRepository.requestedAppointments)
    }

    @Test
    fun `retry reloads after a failure`() = runTest(mainDispatcherRule.dispatcher) {
        bookingRepository.appointmentResult = ApiResult.Failure(ApiError.NoConnection)
        val viewModel = viewModel(AppointmentVertical.Barber)
        runCurrent()

        bookingRepository.appointmentResult = ApiResult.Success(Appointment(id = APPOINTMENT))
        viewModel.onEvent(AppointmentEvent.Retry)
        runCurrent()

        assertTrue(viewModel.state.value.appointment is ScreenState.Content)
    }

    private fun viewModel(vertical: AppointmentVertical) = AppointmentViewModel(
        bookingRepository = bookingRepository,
        hospitalRepository = hospitalRepository,
        savedStateHandle = SavedStateHandle(
            mapOf("appointmentId" to APPOINTMENT, "vertical" to vertical.name),
        ),
    )

    private companion object {
        const val APPOINTMENT = "a-1"
    }
}
