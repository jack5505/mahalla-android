package uz.mahalla.feature.services.ui

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import uz.mahalla.data.prefs.UserProfile
import uz.mahalla.feature.services.domain.ServiceOrderError
import uz.mahalla.feature.services.domain.ServiceRequest
import uz.mahalla.feature.services.domain.ServiceRequestStatus
import uz.mahalla.feature.services.ui.order.ServiceOrderEvent
import uz.mahalla.feature.services.ui.order.ServiceOrderViewModel
import uz.mahalla.testutil.FakeServicesRepository
import uz.mahalla.testutil.FakeUserProfileStore
import uz.mahalla.testutil.MainDispatcherRule

/**
 * Форма заказа услуги (issue #71).
 *
 * Robolectric нужен из-за `SavedStateHandle.toRoute()`: разбор типизированного
 * маршрута идёт через настоящий `Bundle`, а в обычном JVM-тесте android.jar
 * заглушен и `placeId` читался бы как `null`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceOrderViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val repository = FakeServicesRepository()

    @Test
    fun `place comes from the route and the name from the account`() = runTest {
        val viewModel = viewModel(profile = UserProfile(fullName = "Jahongir Sabirov"))

        val state = viewModel.state.value
        assertEquals(PLACE_ID, state.placeId)
        assertEquals(PLACE_NAME, state.placeName)
        // Имя уже известно — переписывать его руками незачем.
        assertEquals("Jahongir Sabirov", state.form.customerName)
    }

    @Test
    fun `empty account name leaves the field empty and does not block the form`() = runTest {
        val viewModel = viewModel(profile = UserProfile())

        assertEquals("", viewModel.state.value.form.customerName)
        assertFalse(viewModel.state.value.canSubmit)
    }

    @Test
    fun `submit of an empty form shows the errors instead of going to the network`() = runTest {
        val viewModel = viewModel(profile = UserProfile())

        viewModel.onEvent(ServiceOrderEvent.SubmitClicked)

        assertTrue(repository.sentOrders.isEmpty())
        val state = viewModel.state.value
        assertTrue(state.validationShown)
        assertNotNull(state.error { it is ServiceOrderError.NameRequired })
        assertNotNull(state.error { it is ServiceOrderError.ServiceRequired })
    }

    @Test
    fun `errors are hidden until the first submit`() = runTest {
        val viewModel = viewModel(profile = UserProfile())

        // Форма пустая, ошибки посчитаны — но краснеть авансом не за что.
        assertTrue(viewModel.state.value.errors.isNotEmpty())
        assertNull(viewModel.state.value.error { it is ServiceOrderError.NameRequired })
    }

    @Test
    fun `filled form is sent for the place of the route`() = runTest {
        repository.orderResult = ApiResult.Success(
            ServiceRequest(id = "w-1", status = ServiceRequestStatus.Waiting, queuePosition = 3),
        )
        val viewModel = viewModel(profile = UserProfile())

        viewModel.onEvent(ServiceOrderEvent.NameChanged("Aziz"))
        viewModel.onEvent(ServiceOrderEvent.ServiceChanged("Soch olish"))
        viewModel.onEvent(ServiceOrderEvent.SubmitClicked)

        assertEquals(1, repository.sentOrders.size)
        assertEquals(PLACE_ID, repository.sentOrders.single().first)
        assertEquals("Aziz", repository.sentOrders.single().second.customerName)

        val state = viewModel.state.value
        assertFalse(state.isSubmitting)
        assertEquals(3, state.request?.queuePosition)
    }

    @Test
    fun `refusal keeps the form and shows the answer of the server`() = runTest {
        repository.orderResult = ApiResult.Failure(ApiError.Forbidden)
        val viewModel = viewModel(profile = UserProfile())

        viewModel.onEvent(ServiceOrderEvent.NameChanged("Aziz"))
        viewModel.onEvent(ServiceOrderEvent.ServiceChanged("Soch olish"))
        viewModel.onEvent(ServiceOrderEvent.SubmitClicked)

        val state = viewModel.state.value
        assertNull(state.request)
        assertEquals(ApiError.Forbidden, state.submitFailure?.error)
        // Набранное не теряется: заполнять форму заново из-за отказа сервера —
        // худшее, что можно сделать.
        assertEquals("Soch olish", state.form.serviceName)
    }

    @Test
    fun `a new order keeps the name and clears the service`() = runTest {
        repository.orderResult = ApiResult.Success(
            ServiceRequest(id = "w-1", status = ServiceRequestStatus.Declined),
        )
        val viewModel = viewModel(profile = UserProfile())

        viewModel.onEvent(ServiceOrderEvent.NameChanged("Aziz"))
        viewModel.onEvent(ServiceOrderEvent.ServiceChanged("Soch olish"))
        viewModel.onEvent(ServiceOrderEvent.SubmitClicked)
        viewModel.onEvent(ServiceOrderEvent.NewOrderRequested)

        val state = viewModel.state.value
        assertNull(state.request)
        assertEquals("Aziz", state.form.customerName)
        assertEquals("", state.form.serviceName)
        assertFalse(state.validationShown)
    }

    private fun viewModel(profile: UserProfile) = ServiceOrderViewModel(
        repository = repository,
        profileStore = FakeUserProfileStore(profile),
        savedStateHandle = SavedStateHandle(
            mapOf("placeId" to PLACE_ID, "placeName" to PLACE_NAME),
        ),
    )

    private companion object {
        const val PLACE_ID = "p-42"
        const val PLACE_NAME = "Barbershop Chilonzor"
    }
}
