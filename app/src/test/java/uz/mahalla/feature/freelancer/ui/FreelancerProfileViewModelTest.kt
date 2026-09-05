package uz.mahalla.feature.freelancer.ui

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
import uz.mahalla.feature.booking.domain.BarberService
import uz.mahalla.feature.freelancer.domain.Freelancer
import uz.mahalla.feature.freelancer.domain.FreelancerOrder
import uz.mahalla.feature.freelancer.domain.FreelancerOrderStatus
import uz.mahalla.feature.freelancer.ui.profile.FreelancerProfileEffect
import uz.mahalla.feature.freelancer.ui.profile.FreelancerProfileEvent
import uz.mahalla.feature.freelancer.ui.profile.FreelancerProfileViewModel
import uz.mahalla.feature.role.data.RoleProfile
import uz.mahalla.feature.role.domain.CustomerForm
import uz.mahalla.testutil.FakeFreelancerRepository
import uz.mahalla.testutil.FakeRoleRepository
import uz.mahalla.testutil.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Профиль мастера и заказ услуги (issue #107).
 *
 * Под Robolectric, потому что `SavedStateHandle.toRoute()` разбирает
 * типизированный маршрут через настоящий `Bundle` — на голой JVM аргументы
 * читаются как `null`, причём молча.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class FreelancerProfileViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeFreelancerRepository()
    private var roleRepository = FakeRoleRepository()

    @Test
    fun `profile and services are two independent requests`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.profileResult = ApiResult.Failure(ApiError.NoConnection)
            repository.servicesResult = ApiResult.Success(listOf(service("s-1"), service("s-2")))

            val viewModel = viewModel()
            runCurrent()

            val state = viewModel.state.value
            // Отказ профиля не прячет услуги, которые уже приехали.
            assertEquals(ApiError.NoConnection, (state.profile as ScreenState.Error).error)
            assertTrue(state.services is ScreenState.Content)
            assertEquals(listOf(FREELANCER), repository.requestedProfiles)
            assertEquals(listOf(FREELANCER), repository.requestedServices)
        }

    /** Имя из маршрута — заглушка шапки, пока едет профиль; сервер точнее. */
    @Test
    fun `server name replaces the one from the route`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.profileResult = ApiResult.Success(
                Freelancer(id = FREELANCER, name = "Aziz Karimov"),
            )

            val viewModel = viewModel()
            assertEquals("Usta", viewModel.state.value.freelancerName)
            runCurrent()

            assertEquals("Aziz Karimov", viewModel.state.value.freelancerName)
        }

    @Test
    fun `the only service is selected on its own`() = runTest(mainDispatcherRule.dispatcher) {
        repository.servicesResult = ApiResult.Success(listOf(service("s-1")))

        val viewModel = viewModel()
        runCurrent()

        assertEquals("s-1", viewModel.state.value.draft.serviceId)
    }

    @Test
    fun `several services are not picked for the human`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.servicesResult = ApiResult.Success(listOf(service("s-1"), service("s-2")))

            val viewModel = viewModel()
            runCurrent()

            assertNull(viewModel.state.value.draft.serviceId)
            assertFalse(viewModel.state.value.canOrder)
        }

    /** День выбран сразу, а время — нет: по умолчанию «как можно скорее». */
    @Test
    fun `today is preselected and the order is asap by default`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.servicesResult = ApiResult.Success(listOf(service("s-1")))

            val viewModel = viewModel()
            runCurrent()

            val state = viewModel.state.value
            assertEquals(TODAY, state.draft.date)
            assertNull(state.draft.time)
            assertNull(state.draft.scheduledAt())
            assertTrue(state.canOrder)
            // Сегодня уже 14:00 — раннего времени в сетке нет.
            assertEquals(LocalTime.of(14, 0), state.times.first())
        }

    @Test
    fun `changing the day resets the chosen hour and rebuilds the grid`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.servicesResult = ApiResult.Success(listOf(service("s-1")))
            val viewModel = viewModel()
            runCurrent()
            viewModel.onEvent(FreelancerProfileEvent.TimeSelected(LocalTime.of(15, 0)))

            viewModel.onEvent(FreelancerProfileEvent.DateSelected(TOMORROW))

            val state = viewModel.state.value
            assertEquals(TOMORROW, state.draft.date)
            assertNull(state.draft.time)
            // Завтрашний день предлагается целиком, с самого утра.
            assertEquals(LocalTime.of(8, 0), state.times.first())
        }

    /** Адрес из анкеты покупателя (issue #84) — чтобы не набирать заново. */
    @Test
    fun `address is prefilled from the customer form`() =
        runTest(mainDispatcherRule.dispatcher) {
            roleRepository = FakeRoleRepository(
                RoleProfile(customer = CustomerForm(address = "Chilonzor 7")),
            )

            val viewModel = viewModel()
            runCurrent()

            assertEquals("Chilonzor 7", viewModel.state.value.draft.address)
        }

    /** Чтение асинхронное: набранное затирать нельзя. */
    @Test
    fun `prefill does not overwrite what the human typed`() =
        runTest(mainDispatcherRule.dispatcher) {
            roleRepository = FakeRoleRepository(
                RoleProfile(customer = CustomerForm(address = "Chilonzor 7")),
            )

            val viewModel = viewModel()
            viewModel.onEvent(FreelancerProfileEvent.AddressChanged("Yunusobod 12"))
            runCurrent()

            assertEquals("Yunusobod 12", viewModel.state.value.draft.address)
        }

    @Test
    fun `order sends the draft and shows the confirmation`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.servicesResult = ApiResult.Success(listOf(service("s-1", "Kran")))
            val viewModel = viewModel()
            runCurrent()
            viewModel.onEvent(FreelancerProfileEvent.TimeSelected(LocalTime.of(15, 0)))
            viewModel.onEvent(FreelancerProfileEvent.CommentChanged("Kran oqyapti"))

            viewModel.onEvent(FreelancerProfileEvent.OrderClicked)
            runCurrent()

            val (freelancerId, draft) = repository.orders.single()
            assertEquals(FREELANCER, freelancerId)
            assertEquals("s-1", draft.serviceId)
            assertEquals(LocalTime.of(15, 0), draft.time)
            assertEquals("Kran oqyapti", draft.commentOrNull())
            val state = viewModel.state.value
            assertFalse(state.isOrdering)
            // Экран не уходит сам: молчаливый переход читается как «ничего не
            // произошло» (issue #49).
            assertEquals("o-1", state.ordered?.id)
        }

    /** Названия услуги сервер может и не вернуть — подставляем выбранное. */
    @Test
    fun `confirmation falls back to the chosen service title`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.servicesResult = ApiResult.Success(listOf(service("s-1", "Kran")))
            repository.orderResult = ApiResult.Success(
                FreelancerOrder(id = "o-1", status = FreelancerOrderStatus.Pending),
            )
            val viewModel = viewModel()
            runCurrent()

            viewModel.onEvent(FreelancerProfileEvent.OrderClicked)
            runCurrent()

            assertEquals("Kran", viewModel.state.value.ordered?.serviceTitle)
        }

    @Test
    fun `failed order keeps what the human typed`() = runTest(mainDispatcherRule.dispatcher) {
        repository.servicesResult = ApiResult.Success(listOf(service("s-1")))
        repository.orderResult = ApiResult.Failure(ApiError.NoConnection)
        val viewModel = viewModel()
        runCurrent()
        viewModel.onEvent(FreelancerProfileEvent.CommentChanged("Kran oqyapti"))

        viewModel.onEvent(FreelancerProfileEvent.OrderClicked)
        runCurrent()

        val state = viewModel.state.value
        assertEquals(ApiError.NoConnection, state.orderFailure?.error)
        assertEquals("Kran oqyapti", state.draft.comment)
        assertNull(state.ordered)
        assertFalse(state.isOrdering)
    }

    /** Отказ был про другой текст: правка его снимает. */
    @Test
    fun `editing the comment clears the previous failure`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.servicesResult = ApiResult.Success(listOf(service("s-1")))
            repository.orderResult = ApiResult.Failure(ApiError.NoConnection)
            val viewModel = viewModel()
            runCurrent()
            viewModel.onEvent(FreelancerProfileEvent.OrderClicked)
            runCurrent()

            viewModel.onEvent(FreelancerProfileEvent.CommentChanged("boshqa"))

            assertNull(viewModel.state.value.orderFailure)
        }

    /** Мастер сам сказал, что заказы не берёт: кнопка выключена. */
    @Test
    fun `busy freelancer cannot be ordered from`() = runTest(mainDispatcherRule.dispatcher) {
        repository.profileResult = ApiResult.Success(
            Freelancer(id = FREELANCER, name = "Aziz", isAvailable = false),
        )
        repository.servicesResult = ApiResult.Success(listOf(service("s-1")))
        val viewModel = viewModel()
        runCurrent()

        assertTrue(viewModel.state.value.isUnavailable)
        assertFalse(viewModel.state.value.canOrder)

        viewModel.onEvent(FreelancerProfileEvent.OrderClicked)
        runCurrent()

        assertTrue(repository.orders.isEmpty())
    }

    /**
     * Профиль не приехал — заказ не запрещаем: последнее слово за сервером, а
     * выключенная кнопка из-за неудавшегося запроса объясняла бы не то.
     */
    @Test
    fun `failed profile does not block the order`() = runTest(mainDispatcherRule.dispatcher) {
        repository.profileResult = ApiResult.Failure(ApiError.NoConnection)
        repository.servicesResult = ApiResult.Success(listOf(service("s-1")))

        val viewModel = viewModel()
        runCurrent()

        assertFalse(viewModel.state.value.isUnavailable)
        assertTrue(viewModel.state.value.canOrder)
    }

    @Test
    fun `second tap does not create a second order`() = runTest(mainDispatcherRule.dispatcher) {
        repository.servicesResult = ApiResult.Success(listOf(service("s-1")))
        val viewModel = viewModel()
        runCurrent()

        viewModel.onEvent(FreelancerProfileEvent.OrderClicked)
        viewModel.onEvent(FreelancerProfileEvent.OrderClicked)
        runCurrent()
        viewModel.onEvent(FreelancerProfileEvent.OrderClicked)
        runCurrent()

        assertEquals(1, repository.orders.size)
    }

    @Test
    fun `call uses the phone from the profile`() = runTest(mainDispatcherRule.dispatcher) {
        repository.profileResult = ApiResult.Success(
            Freelancer(id = FREELANCER, name = "Aziz", phone = "+998901234567"),
        )
        val viewModel = viewModel()
        runCurrent()

        val effects = mutableListOf<FreelancerProfileEffect>()
        val job = launch { effects += viewModel.effects.first() }
        viewModel.onEvent(FreelancerProfileEvent.CallClicked)
        runCurrent()
        job.join()

        assertEquals(FreelancerProfileEffect.Dial("+998901234567"), effects.single())
    }

    /** Номера нет — звонить нечем, и тап не должен ничего запускать. */
    @Test
    fun `call without a phone does nothing`() = runTest(mainDispatcherRule.dispatcher) {
        repository.profileResult = ApiResult.Success(Freelancer(id = FREELANCER, name = "Aziz"))
        val viewModel = viewModel()
        runCurrent()

        val effects = mutableListOf<FreelancerProfileEffect>()
        val job = launch { viewModel.effects.collect { effects += it } }
        viewModel.onEvent(FreelancerProfileEvent.CallClicked)
        runCurrent()
        job.cancel()

        assertTrue(effects.isEmpty())
    }

    private fun viewModel() = FreelancerProfileViewModel(
        repository = repository,
        roleRepository = roleRepository,
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
        savedStateHandle = SavedStateHandle(
            mapOf("freelancerId" to FREELANCER, "freelancerName" to "Usta"),
        ),
    )

    private fun service(id: String, title: String = "Xizmat $id") =
        BarberService(id = id, title = title, priceSum = 150_000)

    private companion object {
        /** 09:00 UTC = 14:00 в Ташкенте, 4 сентября. */
        val NOW: Instant = Instant.parse("2026-09-04T09:00:00Z")
        val TODAY: LocalDate = LocalDate.of(2026, 9, 4)
        val TOMORROW: LocalDate = LocalDate.of(2026, 9, 5)
        const val FREELANCER = "f-1"
    }
}
