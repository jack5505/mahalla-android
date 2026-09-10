package uz.mahalla.feature.booking.ui.appointments

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
 * «Мои записи» (issue #97): разделы, догрузка, отмена с подтверждением и
 * перенос на другое время (эпик #11).
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

    // --- Перенос записи (эпик #11) ---

    @Test
    fun `rescheduling sends the place and the service of the record onward`() = runTest {
        repository.defaultPage = page(
            listOf(appointment("a-1", LocalDate.of(2026, 9, 5), time = LocalTime.of(10, 40))),
        )
        val viewModel = viewModel()

        viewModel.onEvent(MyAppointmentsEvent.RescheduleRequested("a-1"))
        val effects = collectEffects(viewModel)

        // Взять их больше негде: своего экрана у одной записи нет, а
        // `POST appointments` без заведения и услуги не примут. Подпись и
        // прежнее время едут туда же (issue #155): без них перенос
        // подтверждают, не видя, что и с какого времени переносят.
        assertEquals(
            listOf(
                MyAppointmentsEffect.OpenReschedule(
                    RescheduleTarget(
                        appointmentId = "a-1",
                        placeId = "p-1",
                        serviceId = "s-1",
                        serviceName = "Soch olish",
                        date = LocalDate.of(2026, 9, 5),
                        startTime = LocalTime.of(10, 40),
                    ),
                ),
            ),
            effects,
        )
    }

    /**
     * Услугу сервер называть не обязан (`AppointmentResponse`), и запись без
     * имени переносить всё равно можно: подпись просто уезжает пустой, а имя
     * экран переноса поищет в каталоге заведения.
     */
    @Test
    fun `a record without a service name still travels, just without a label`() = runTest {
        repository.defaultPage = page(
            listOf(appointment("a-1", LocalDate.of(2026, 9, 5), serviceName = null)),
        )
        val viewModel = viewModel()

        viewModel.onEvent(MyAppointmentsEvent.RescheduleRequested("a-1"))
        val effects = collectEffects(viewModel)

        val target = (effects.single() as MyAppointmentsEffect.OpenReschedule).target
        assertEquals("", target.serviceName)
        assertEquals("s-1", target.serviceId)
    }

    @Test
    fun `what cannot be rescheduled does not open the booking screen`() = runTest {
        repository.defaultPage = page(
            listOf(
                appointment("no-place", LocalDate.of(2026, 9, 5), placeId = null),
                appointment("no-service", LocalDate.of(2026, 9, 5), serviceId = null),
                appointment("done", LocalDate.of(2026, 9, 5), AppointmentStatus.Completed),
            ),
        )
        val viewModel = viewModel()

        // Нажатие могло устареть: список перечитывается на каждом возврате, и
        // заведение успело бы закрыть запись.
        listOf("no-place", "no-service", "done", "unknown").forEach { id ->
            viewModel.onEvent(MyAppointmentsEvent.RescheduleRequested(id))
        }

        assertTrue(collectEffects(viewModel).isEmpty())
    }

    @Test
    fun `nothing is rescheduled while a cancellation is going on`() = runTest {
        repository.defaultPage = page(
            listOf(
                appointment("a-1", LocalDate.of(2026, 9, 5)),
                appointment("a-2", LocalDate.of(2026, 9, 6)),
            ),
        )
        // Отмена, которая ещё не ответила: пока она висит, остальные строки не
        // трогаем — ответ приехал бы на список, которого уже нет (то же
        // правило, что у устройств в профиле, issue #61).
        val gate = CompletableDeferred<Unit>()
        repository.cancelGate = gate
        val viewModel = viewModel()
        viewModel.onEvent(MyAppointmentsEvent.CancelRequested("a-1"))
        viewModel.onEvent(MyAppointmentsEvent.CancelConfirmed)
        assertEquals("a-1", viewModel.state.value.pendingCancelId)

        viewModel.onEvent(MyAppointmentsEvent.RescheduleRequested("a-2"))

        assertTrue(collectEffects(viewModel).isEmpty())

        // А как отмена закончилась — перенос снова доступен.
        gate.complete(Unit)
        viewModel.onEvent(MyAppointmentsEvent.RescheduleRequested("a-2"))
        assertEquals(1, collectEffects(viewModel).size)
    }

    @Test
    fun `a doctor appointment is not rescheduled from here`() = runTest {
        hospitalRepository.defaultPage = page(
            listOf(appointment("d-1", LocalDate.of(2026, 9, 5))),
        )
        val viewModel = viewModel(vertical = AppointmentVertical.Doctor)

        viewModel.onEvent(MyAppointmentsEvent.RescheduleRequested("d-1"))

        // Вторая половина переноса у врача другая: `hospitals/appointments`
        // ждёт `doctorId` и жалобу, и `serviceId` там записал бы не к тому.
        assertFalse(viewModel.state.value.canReschedule)
        assertTrue(collectEffects(viewModel).isEmpty())
    }

    /**
     * Эффекты лежат в буферизованном канале, поэтому собираются **после**
     * события: подписчик ничего не теряет, а «эффекта не было» иначе не
     * проверить — `first()` на пустом канале просто повис бы.
     */
    private fun TestScope.collectEffects(
        viewModel: MyAppointmentsViewModel,
    ): List<MyAppointmentsEffect> {
        val effects = mutableListOf<MyAppointmentsEffect>()
        val job = launch { viewModel.effects.collect { effects += it } }
        runCurrent()
        job.cancel()
        return effects
    }

    private fun appointment(
        id: String,
        date: LocalDate,
        status: AppointmentStatus = AppointmentStatus.Pending,
        time: LocalTime = LocalTime.of(10, 0),
        placeId: String? = "p-1",
        serviceId: String? = "s-1",
        serviceName: String? = "Soch olish",
    ) = Appointment(
        id = id,
        placeId = placeId,
        serviceId = serviceId,
        serviceName = serviceName,
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
