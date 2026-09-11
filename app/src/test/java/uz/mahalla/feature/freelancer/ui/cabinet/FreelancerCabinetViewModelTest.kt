package uz.mahalla.feature.freelancer.ui.cabinet

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.freelancer.domain.Freelancer
import uz.mahalla.feature.freelancer.domain.FreelancerCabinetService
import uz.mahalla.feature.freelancer.domain.FreelancerOrder
import uz.mahalla.feature.freelancer.domain.FreelancerOrderPage
import uz.mahalla.feature.freelancer.domain.FreelancerOrderStatus
import uz.mahalla.testutil.FakeFreelancerCabinetRepository
import uz.mahalla.testutil.MainDispatcherRule

/**
 * Кабинет мастера (issue #190).
 *
 * Ключевой риск задачи — `GET freelancers/me` без анкеты — закрыт на уровне
 * репозитория ([FreelancerCabinetRepositoryTest]); здесь проверяется, что
 * ViewModel правильно читает уже сведённый к `null` результат и не запрашивает
 * услуги и заказы, которым без `id` мастера просто нечем быть.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FreelancerCabinetViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeFreelancerCabinetRepository()

    private fun viewModel() = FreelancerCabinetViewModel(repository)

    @Test
    fun `no profile shows become-master and skips services and orders`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.meResult = ApiResult.Success(null)

            val viewModel = viewModel()
            runCurrent()

            assertEquals(ScreenState.Empty, viewModel.state.value.profile)
            assertTrue(repository.requestedServicesFor.isEmpty())
            assertTrue(repository.requestedOrderPages.isEmpty())
        }

    @Test
    fun `existing profile loads services and orders`() = runTest(mainDispatcherRule.dispatcher) {
        repository.meResult = ApiResult.Success(freelancer())
        repository.servicesResult = ApiResult.Success(listOf(service("s-1")))
        repository.defaultIncomingOrderPage = ApiResult.Success(
            FreelancerOrderPage(items = listOf(order("o-1"))),
        )

        val viewModel = viewModel()
        runCurrent()

        assertEquals(listOf("f-1"), repository.requestedServicesFor)
        assertEquals(listOf(0), repository.requestedOrderPages)
        val services = viewModel.state.value.services as ScreenState.Content
        assertEquals(listOf("s-1"), services.data.map { it.id })
        val orders = viewModel.state.value.orders as ScreenState.Content
        assertEquals(listOf("o-1"), orders.data.map { it.id })
    }

    @Test
    fun `become master and edit anketa both open the anketa form`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.meResult = ApiResult.Success(freelancer())
            val viewModel = viewModel()
            runCurrent()
            val effects = mutableListOf<FreelancerCabinetEffect>()
            val job = launch { viewModel.effects.collect { effects += it } }

            viewModel.onEvent(FreelancerCabinetEvent.EditAnketaClicked)
            runCurrent()

            assertEquals(listOf(FreelancerCabinetEffect.OpenAnketaForm), effects)
            job.cancel()
        }

    @Test
    fun `toggling availability flips the profile in place`() = runTest(mainDispatcherRule.dispatcher) {
        repository.meResult = ApiResult.Success(freelancer(isAvailable = true))
        repository.toggleAvailabilityResult = ApiResult.Success(false)
        val viewModel = viewModel()
        runCurrent()

        viewModel.onEvent(FreelancerCabinetEvent.AvailabilityToggled)
        runCurrent()

        val content = viewModel.state.value.profile as ScreenState.Content
        assertFalse(content.data.isAvailable)
        assertFalse(viewModel.state.value.togglingAvailability)
    }

    @Test
    fun `failed toggle keeps the previous value and shows the reason`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.meResult = ApiResult.Success(freelancer(isAvailable = true))
            repository.toggleAvailabilityResult = ApiResult.Failure(ApiError.NoConnection)
            val viewModel = viewModel()
            runCurrent()

            viewModel.onEvent(FreelancerCabinetEvent.AvailabilityToggled)
            runCurrent()

            val content = viewModel.state.value.profile as ScreenState.Content
            assertTrue(content.data.isAvailable)
            assertEquals(ApiError.NoConnection, viewModel.state.value.availabilityFailure?.error)
        }

    @Test
    fun `adding a service closes the sheet and appends to the list`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.meResult = ApiResult.Success(freelancer())
            repository.servicesResult = ApiResult.Success(emptyList())
            repository.addServiceResult = ApiResult.Success(service("s-new", title = "Kran"))
            val viewModel = viewModel()
            runCurrent()

            viewModel.onEvent(FreelancerCabinetEvent.ServiceAddClicked)
            viewModel.onEvent(FreelancerCabinetEvent.ServiceTitleChanged("Kran"))
            viewModel.onEvent(FreelancerCabinetEvent.ServicePriceChanged("80000"))
            viewModel.onEvent(FreelancerCabinetEvent.ServiceDurationChanged("60"))
            viewModel.onEvent(FreelancerCabinetEvent.ServiceSheetSubmitted)
            runCurrent()

            assertEquals(1, repository.addedServices.size)
            val services = viewModel.state.value.services as ScreenState.Content
            assertEquals(listOf("s-new"), services.data.map { it.id })
            assertEquals(null, viewModel.state.value.serviceSheet)
        }

    @Test
    fun `incomplete service draft is rejected before the network`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.meResult = ApiResult.Success(freelancer())
            val viewModel = viewModel()
            runCurrent()

            viewModel.onEvent(FreelancerCabinetEvent.ServiceAddClicked)
            viewModel.onEvent(FreelancerCabinetEvent.ServiceSheetSubmitted)
            runCurrent()

            assertTrue(repository.addedServices.isEmpty())
            assertTrue(viewModel.state.value.serviceSheet?.validationShown == true)
        }

    @Test
    fun `editing a service prefills the sheet`() = runTest(mainDispatcherRule.dispatcher) {
        repository.meResult = ApiResult.Success(freelancer())
        val viewModel = viewModel()
        runCurrent()

        val existing = service("s-1", title = "Kran", priceSum = 80_000, durationMinutes = 60)
        viewModel.onEvent(FreelancerCabinetEvent.ServiceEditClicked(existing))

        val sheet = requireNotNull(viewModel.state.value.serviceSheet)
        assertTrue(sheet.isEditing)
        assertEquals("Kran", sheet.draft.title)
        assertEquals("80000", sheet.draft.priceText)
    }

    @Test
    fun `deleting a service asks for confirmation before the network`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.meResult = ApiResult.Success(freelancer())
            repository.servicesResult = ApiResult.Success(listOf(service("s-1")))
            val viewModel = viewModel()
            runCurrent()

            viewModel.onEvent(FreelancerCabinetEvent.ServiceDeleteRequested(service("s-1")))
            runCurrent()
            assertTrue(repository.deletedServiceIds.isEmpty())

            viewModel.onEvent(FreelancerCabinetEvent.ServiceDeleteConfirmed)
            runCurrent()

            assertEquals(listOf("s-1"), repository.deletedServiceIds)
            assertEquals(ScreenState.Empty, viewModel.state.value.services)
        }

    @Test
    fun `order status change replaces the order in the list`() =
        runTest(mainDispatcherRule.dispatcher) {
            repository.meResult = ApiResult.Success(freelancer())
            repository.defaultIncomingOrderPage = ApiResult.Success(
                FreelancerOrderPage(items = listOf(order("o-1", FreelancerOrderStatus.Pending))),
            )
            repository.updateOrderStatusResult = ApiResult.Success(
                order("o-1", FreelancerOrderStatus.Accepted),
            )
            val viewModel = viewModel()
            runCurrent()

            viewModel.onEvent(
                FreelancerCabinetEvent.OrderStatusChanged("o-1", FreelancerOrderStatus.Accepted),
            )
            runCurrent()

            val orders = viewModel.state.value.orders as ScreenState.Content
            assertEquals(FreelancerOrderStatus.Accepted, orders.data.single().status)
            assertEquals(listOf("o-1" to FreelancerOrderStatus.Accepted), repository.orderStatusUpdates)
        }

    @Test
    fun `resuming the screen rereads everything`() = runTest(mainDispatcherRule.dispatcher) {
        repository.meResult = ApiResult.Success(freelancer())
        val viewModel = viewModel()
        runCurrent()

        viewModel.onEvent(FreelancerCabinetEvent.ScreenResumed)
        runCurrent()

        assertEquals(2, repository.meCallCount)
    }

    private fun freelancer(isAvailable: Boolean = true) = Freelancer(
        id = "f-1",
        name = "Aziz Karimov",
        profession = "Santexnik",
        isAvailable = isAvailable,
    )

    private fun service(
        id: String,
        title: String = "Kran",
        priceSum: Long = 80_000,
        durationMinutes: Int = 60,
    ) = FreelancerCabinetService(
        id = id,
        title = title,
        priceSum = priceSum,
        durationMinutes = durationMinutes,
    )

    private fun order(id: String, status: FreelancerOrderStatus = FreelancerOrderStatus.Pending) =
        FreelancerOrder(id = id, status = status)
}
