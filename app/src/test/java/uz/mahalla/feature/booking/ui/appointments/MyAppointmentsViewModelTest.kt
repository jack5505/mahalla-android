package uz.mahalla.feature.booking.ui.appointments

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
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.booking.domain.Appointment
import uz.mahalla.feature.booking.domain.AppointmentPage
import uz.mahalla.feature.booking.domain.AppointmentStatus
import uz.mahalla.feature.booking.domain.AppointmentVertical
import uz.mahalla.navigation.MyAppointmentsArgs
import uz.mahalla.testutil.FakeBookingRepository
import uz.mahalla.testutil.FakeHospitalRepository
import uz.mahalla.testutil.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * «Мои записи» (issue #97): разделы, догрузка и отмена с подтверждением.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MyAppointmentsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val repository = FakeBookingRepository()
    private val hospitalRepository = FakeHospitalRepository()

    @Test
    fun `appointments are split into upcoming and past`() = runTest {
        repository.defaultPage = page(
            listOf(
                appointment("past", LocalDate.of(2026, 9, 1), AppointmentStatus.Completed),
                appointment("soon", LocalDate.of(2026, 9, 5)),
            ),
        )

        val state = viewModel().state.value

        assertEquals(listOf("soon"), state.sections.upcoming.map(Appointment::id))
        assertEquals(listOf("past"), state.sections.past.map(Appointment::id))
        assertEquals(listOf(0), repository.requestedPages)
    }

    @Test
    fun `an empty answer is an empty state, not an error`() = runTest {
        val state = viewModel().state.value

        assertTrue(state.appointments is ScreenState.Empty)
        assertTrue(state.sections.isEmpty)
        assertFalse(state.hasMore)
    }

    @Test
    fun `a refusal is shown with the text of the server`() = runTest {
        repository.defaultPage = ApiResult.Failure(ApiError.Business("APPOINTMENTS_UNAVAILABLE"))

        val state = viewModel().state.value

        assertEquals(
            ApiError.Business("APPOINTMENTS_UNAVAILABLE"),
            (state.appointments as ScreenState.Error).error,
        )
    }

    /**
     * Статус меняет заведение из своей панели — показанное час назад «ждёт
     * подтверждения» ничего не стоит.
     */
    @Test
    fun `coming back to the screen rereads the list`() = runTest {
        repository.defaultPage = page(listOf(appointment("a-1", LocalDate.of(2026, 9, 5))))
        val viewModel = viewModel()

        repository.defaultPage = page(
            listOf(
                appointment("a-1", LocalDate.of(2026, 9, 5), AppointmentStatus.Confirmed),
            ),
        )
        viewModel.onEvent(MyAppointmentsEvent.ScreenResumed)

        assertEquals(listOf(0, 0), repository.requestedPages)
        assertEquals(
            AppointmentStatus.Confirmed,
            viewModel.state.value.sections.upcoming.single().status,
        )
    }

    @Test
    fun `the next page is appended and duplicates are dropped`() = runTest {
        repository.pages[0] = page(
            listOf(appointment("a-1", LocalDate.of(2026, 9, 5))),
            hasMore = true,
        )
        repository.pages[1] = page(
            listOf(
                appointment("a-1", LocalDate.of(2026, 9, 5)),
                appointment("a-2", LocalDate.of(2026, 9, 6)),
            ),
        )
        val viewModel = viewModel()

        viewModel.onEvent(MyAppointmentsEvent.LoadMore)

        // Дубликат ключа уронил бы `LazyColumn`.
        assertEquals(
            listOf("a-1", "a-2"),
            (viewModel.state.value.appointments as ScreenState.Content).data.map(Appointment::id),
        )
        assertEquals(listOf("a-1", "a-2"), viewModel.state.value.sections.upcoming.map { it.id })
        assertFalse(viewModel.state.value.hasMore)
    }

    @Test
    fun `a failed page keeps the list and offers a retry`() = runTest {
        repository.pages[0] = page(
            listOf(appointment("a-1", LocalDate.of(2026, 9, 5))),
            hasMore = true,
        )
        repository.pages[1] = ApiResult.Failure(ApiError.NoConnection)
        val viewModel = viewModel()

        viewModel.onEvent(MyAppointmentsEvent.LoadMore)

        val state = viewModel.state.value
        assertEquals(ApiError.NoConnection, state.loadMoreFailure?.error)
        assertFalse(state.isLoadingMore)
        assertEquals(1, (state.appointments as ScreenState.Content).data.size)
    }

    @Test
    fun `cancelling asks for confirmation first`() = runTest {
        repository.defaultPage = page(listOf(appointment("a-1", LocalDate.of(2026, 9, 5))))
        val viewModel = viewModel()

        viewModel.onEvent(MyAppointmentsEvent.CancelRequested("a-1"))

        assertEquals("a-1", viewModel.state.value.confirmCancel?.id)
        // Пока не подтвердили — в сеть ничего не ушло.
        assertTrue(repository.cancelled.isEmpty())

        viewModel.onEvent(MyAppointmentsEvent.CancelDismissed)
        assertNull(viewModel.state.value.confirmCancel)
        assertTrue(repository.cancelled.isEmpty())
    }

    @Test
    fun `a cancelled appointment moves to the past instead of disappearing`() = runTest {
        repository.defaultPage = page(listOf(appointment("a-1", LocalDate.of(2026, 9, 5))))
        val viewModel = viewModel()

        viewModel.onEvent(MyAppointmentsEvent.CancelRequested("a-1"))
        viewModel.onEvent(MyAppointmentsEvent.CancelConfirmed)

        assertEquals(listOf("a-1"), repository.cancelled)
        val state = viewModel.state.value
        // Список правится на месте: перезагрузка сбросила бы догруженный хвост.
        assertEquals(listOf(0), repository.requestedPages)
        assertTrue(state.sections.upcoming.isEmpty())
        assertEquals(AppointmentStatus.Cancelled, state.sections.past.single().status)
        assertNull(state.pendingCancelId)
    }

    @Test
    fun `a refused cancellation keeps the appointment and explains itself`() = runTest {
        repository.defaultPage = page(listOf(appointment("a-1", LocalDate.of(2026, 9, 5))))
        repository.cancelResult = ApiResult.Failure(
            ApiError.Business("APPOINTMENT_ALREADY_STARTED"),
        )
        val viewModel = viewModel()

        viewModel.onEvent(MyAppointmentsEvent.CancelRequested("a-1"))
        viewModel.onEvent(MyAppointmentsEvent.CancelConfirmed)

        val state = viewModel.state.value
        assertEquals(
            ApiError.Business("APPOINTMENT_ALREADY_STARTED"),
            state.cancelFailure?.error,
        )
        assertEquals(
            AppointmentStatus.Pending,
            state.sections.upcoming.single().status,
        )
        assertNull(state.pendingCancelId)
    }

    @Test
    fun `a finished appointment is not offered for cancelling`() = runTest {
        repository.defaultPage = page(
            listOf(appointment("a-1", LocalDate.of(2026, 9, 1), AppointmentStatus.Completed)),
        )
        val viewModel = viewModel()

        viewModel.onEvent(MyAppointmentsEvent.CancelRequested("a-1"))
        viewModel.onEvent(MyAppointmentsEvent.CancelConfirmed)

        assertNull(viewModel.state.value.confirmCancel)
        assertTrue(repository.cancelled.isEmpty())
    }

    /**
     * Время идёт и без запросов: запись, начавшаяся полчаса назад, на возврате
     * обязана переехать в «прошедшие», иначе экран обещает визит, которого уже
     * не будет.
     */
    @Test
    fun `sections are recounted on resume, not only on load`() = runTest {
        val clock = MovableClock(NOW)
        repository.defaultPage = page(
            listOf(appointment("a-1", LocalDate.of(2026, 9, 4), time = LocalTime.of(15, 0))),
        )
        val viewModel = viewModel(clock)
        assertEquals(listOf("a-1"), viewModel.state.value.sections.upcoming.map { it.id })

        // 11:00 UTC — это 16:00 в Ташкенте, запись на 15:00 уже прошла.
        clock.now = Instant.parse("2026-09-04T11:00:00Z")
        viewModel.onEvent(MyAppointmentsEvent.ScreenResumed)

        assertTrue(viewModel.state.value.sections.upcoming.isEmpty())
        assertEquals(listOf("a-1"), viewModel.state.value.sections.past.map { it.id })
    }

    /**
     * Экран один на обе вертикали (issue #99), и ошибка в выборе источника
     * означала бы чужой список: записи к врачу показывались бы вперемешку с
     * записями к мастеру или вместо них.
     */
    @Test
    fun `the doctor vertical reads the hospital endpoint`() = runTest {
        repository.defaultPage = page(listOf(appointment("barber", LocalDate.of(2026, 9, 5))))
        hospitalRepository.defaultPage = page(
            listOf(appointment("doctor", LocalDate.of(2026, 9, 5))),
        )

        val state = viewModel(vertical = AppointmentVertical.Doctor).state.value

        assertEquals(AppointmentVertical.Doctor, state.vertical)
        assertEquals(listOf("doctor"), state.sections.upcoming.map(Appointment::id))
        assertEquals(listOf(0), hospitalRepository.requestedPages)
        assertTrue(repository.requestedPages.isEmpty())
    }

    @Test
    fun `the doctor vertical cancels through its own source`() = runTest {
        hospitalRepository.defaultPage = page(
            listOf(appointment("a-1", LocalDate.of(2026, 9, 5))),
        )
        val viewModel = viewModel(vertical = AppointmentVertical.Doctor)

        viewModel.onEvent(MyAppointmentsEvent.CancelRequested("a-1"))
        viewModel.onEvent(MyAppointmentsEvent.CancelConfirmed)

        assertEquals(listOf("a-1"), hospitalRepository.cancelled)
        assertTrue(repository.cancelled.isEmpty())
        assertEquals(
            AppointmentStatus.Cancelled,
            viewModel.state.value.sections.past.single().status,
        )
    }

    /** Без аргумента и с мусором в нём экран остаётся списком записей к мастеру. */
    @Test
    fun `an unknown vertical falls back to the barber list`() = runTest {
        repository.defaultPage = page(listOf(appointment("barber", LocalDate.of(2026, 9, 5))))

        val state = MyAppointmentsViewModel(
            bookingRepository = repository,
            hospitalRepository = hospitalRepository,
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
            savedStateHandle = SavedStateHandle(mapOf(MyAppointmentsArgs.VERTICAL to "nonsense")),
        ).state.value

        assertEquals(AppointmentVertical.Barber, state.vertical)
        assertEquals(listOf("barber"), state.sections.upcoming.map(Appointment::id))
        assertTrue(hospitalRepository.requestedPages.isEmpty())
    }

    private fun appointment(
        id: String,
        date: LocalDate,
        status: AppointmentStatus = AppointmentStatus.Pending,
        time: LocalTime = LocalTime.of(10, 0),
    ) = Appointment(
        id = id,
        serviceName = "Soch olish",
        date = date,
        startTime = time,
        status = status,
    )

    private fun page(items: List<Appointment>, hasMore: Boolean = false) =
        ApiResult.Success(AppointmentPage(items = items, hasMore = hasMore))

    private fun viewModel(
        clock: Clock = Clock.fixed(NOW, ZoneOffset.UTC),
        vertical: AppointmentVertical? = null,
    ) = MyAppointmentsViewModel(
        bookingRepository = repository,
        hospitalRepository = hospitalRepository,
        clock = clock,
        // Аргумент маршрута читается по имени, а не через `toRoute()` — иначе
        // тест пришлось бы гонять под Robolectric ради одной строки.
        savedStateHandle = SavedStateHandle(
            vertical?.let { mapOf(MyAppointmentsArgs.VERTICAL to it.name) }.orEmpty(),
        ),
    )

    /** Часы, которые можно подвинуть: деление на разделы зависит от них. */
    private class MovableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
    }

    private companion object {
        /** 09:00 UTC = 14:00 в Ташкенте, 4 сентября. */
        val NOW: Instant = Instant.parse("2026-09-04T09:00:00Z")
    }
}
