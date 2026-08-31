package uz.mahalla.feature.services.ui

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
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.data.prefs.UserProfile
import uz.mahalla.feature.onboarding.domain.PhoneNumberValidator
import uz.mahalla.feature.services.domain.ServiceOffer
import uz.mahalla.feature.services.domain.ServiceOfferError
import uz.mahalla.feature.services.ui.offer.ServiceOfferEvent
import uz.mahalla.feature.services.ui.offer.ServiceOfferViewModel
import uz.mahalla.testutil.FakeServicesRepository
import uz.mahalla.testutil.FakeUserProfileStore
import uz.mahalla.testutil.MainDispatcherRule

/** Форма выставления услуги (issue #71). */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceOfferViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val repository = FakeServicesRepository()

    @Test
    fun `first visit prefills the form from the account`() = runTest {
        repository.offerResult = ApiResult.Success(null)

        val state = viewModel(UserProfile(fullName = "Jahongir", phone = "+998901234567"))
            .state.value

        assertFalse(state.isLoading)
        // Анкеты ещё нет — это не ошибка, а первый вход в форму.
        assertNull(state.offer)
        assertEquals("Jahongir", state.form.name)
        assertEquals("901234567", state.form.phoneDigits)
    }

    @Test
    fun `existing offer opens the form on its own data`() = runTest {
        repository.offerResult = ApiResult.Success(
            ServiceOffer(
                id = "f-1",
                name = "Jahongir",
                profession = "Sartarosh",
                city = "Toshkent",
                hourlyRateSum = 80_000,
                isAvailable = false,
            ),
        )

        val state = viewModel().state.value

        assertEquals("Sartarosh", state.form.profession)
        assertEquals("80000", state.form.hourlyRate)
        assertEquals(false, state.offer?.isAvailable)
        assertTrue(state.canSave)
    }

    @Test
    fun `unreadable offer blocks the form instead of overwriting it`() = runTest {
        repository.offerResult = ApiResult.Failure(ApiError.NoConnection)

        val viewModel = viewModel()

        // Сохранение поверх непрочитанной анкеты затёрло бы её пустыми полями.
        assertEquals(ApiError.NoConnection, viewModel.state.value.loadFailure?.error)

        repository.offerResult = ApiResult.Success(ServiceOffer(id = "f-1", name = "Jahongir"))
        viewModel.onEvent(ServiceOfferEvent.RetryRequested)

        assertNull(viewModel.state.value.loadFailure)
        assertEquals("Jahongir", viewModel.state.value.form.name)
    }

    @Test
    fun `save of an incomplete form shows the errors instead of going to the network`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(ServiceOfferEvent.SaveClicked)

        assertTrue(repository.savedOffers.isEmpty())
        assertNotNull(viewModel.state.value.error { it is ServiceOfferError.ProfessionRequired })
    }

    @Test
    fun `saved form comes back from the answer of the server`() = runTest {
        repository.saveResult = ApiResult.Success(
            ServiceOffer(id = "f-1", name = "Jahongir", profession = "Sartarosh", city = "Toshkent"),
        )
        val viewModel = viewModel()

        viewModel.onEvent(ServiceOfferEvent.NameChanged("Jahongir"))
        viewModel.onEvent(ServiceOfferEvent.ProfessionChanged("Sartarosh"))
        viewModel.onEvent(ServiceOfferEvent.CityChanged("Toshkent"))
        viewModel.onEvent(ServiceOfferEvent.SaveClicked)

        assertEquals(1, repository.savedOffers.size)
        assertEquals("Sartarosh", repository.savedOffers.single().profession)
        val state = viewModel.state.value
        assertTrue(state.saved)
        assertEquals("f-1", state.offer?.id)
    }

    @Test
    fun `editing after saving removes the confirmation`() = runTest {
        repository.saveResult = ApiResult.Success(
            ServiceOffer(id = "f-1", name = "Jahongir", profession = "Sartarosh", city = "Toshkent"),
        )
        val viewModel = viewModel()
        viewModel.onEvent(ServiceOfferEvent.NameChanged("Jahongir"))
        viewModel.onEvent(ServiceOfferEvent.ProfessionChanged("Sartarosh"))
        viewModel.onEvent(ServiceOfferEvent.CityChanged("Toshkent"))
        viewModel.onEvent(ServiceOfferEvent.SaveClicked)

        viewModel.onEvent(ServiceOfferEvent.BioChanged("10 yil tajriba"))

        // «Сохранено» поверх изменённых полей — вранье.
        assertFalse(viewModel.state.value.saved)
    }

    @Test
    fun `refusal of the save is shown with the answer of the server`() = runTest {
        repository.saveResult = ApiResult.Failure(ApiError.Business("PROFILE_BLOCKED"))
        val viewModel = viewModel()

        viewModel.onEvent(ServiceOfferEvent.NameChanged("Jahongir"))
        viewModel.onEvent(ServiceOfferEvent.ProfessionChanged("Sartarosh"))
        viewModel.onEvent(ServiceOfferEvent.CityChanged("Toshkent"))
        viewModel.onEvent(ServiceOfferEvent.SaveClicked)

        assertEquals(
            ApiError.Business("PROFILE_BLOCKED"),
            viewModel.state.value.saveFailure?.error,
        )
        assertFalse(viewModel.state.value.saved)
    }

    @Test
    fun `only digits get into the price and the experience`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(ServiceOfferEvent.RateChanged("80 000 so'm"))
        viewModel.onEvent(ServiceOfferEvent.ExperienceChanged("10 yil"))

        assertEquals("80000", viewModel.state.value.form.hourlyRate)
        assertEquals("10", viewModel.state.value.form.experienceYears)
    }

    @Test
    fun `availability is re-read from the server after the toggle`() = runTest {
        repository.offerResult = ApiResult.Success(ServiceOffer(id = "f-1", isAvailable = true))
        val viewModel = viewModel()
        repository.offerResult = ApiResult.Success(ServiceOffer(id = "f-1", isAvailable = false))

        viewModel.onEvent(ServiceOfferEvent.AvailabilityToggled)

        assertEquals(1, repository.toggleCount)
        // Значение приходит от сервера: сам переключатель желаемого состояния
        // не передаёт.
        assertEquals(2, repository.offerCount)
        assertEquals(false, viewModel.state.value.offer?.isAvailable)
        assertFalse(viewModel.state.value.availabilityPending)
    }

    @Test
    fun `refused toggle leaves the switch where it was`() = runTest {
        repository.offerResult = ApiResult.Success(ServiceOffer(id = "f-1", isAvailable = true))
        repository.toggleResult = ApiResult.Failure(ApiError.NoConnection)
        val viewModel = viewModel()

        viewModel.onEvent(ServiceOfferEvent.AvailabilityToggled)

        assertEquals(true, viewModel.state.value.offer?.isAvailable)
        assertEquals(ApiError.NoConnection, viewModel.state.value.saveFailure?.error)
        assertFalse(viewModel.state.value.availabilityPending)
    }

    @Test
    fun `there is nothing to toggle while the offer does not exist`() = runTest {
        repository.offerResult = ApiResult.Success(null)
        val viewModel = viewModel()

        viewModel.onEvent(ServiceOfferEvent.AvailabilityToggled)

        assertEquals(0, repository.toggleCount)
    }

    private fun viewModel(profile: UserProfile = UserProfile()) = ServiceOfferViewModel(
        repository = repository,
        profileStore = FakeUserProfileStore(profile),
        phoneNumbers = PhoneNumberValidator(),
    )
}
