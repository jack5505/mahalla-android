package uz.mahalla.feature.role.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
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
import uz.mahalla.data.prefs.UserProfile
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.onboarding.domain.City
import uz.mahalla.feature.onboarding.domain.PhoneNumberValidator
import uz.mahalla.feature.role.data.RoleProfile
import uz.mahalla.feature.role.domain.CustomerForm
import uz.mahalla.feature.role.domain.PlaceModerationStatus
import uz.mahalla.feature.role.domain.ProviderFormError
import uz.mahalla.feature.role.domain.RegisteredPlace
import uz.mahalla.testutil.FakeProviderRepository
import uz.mahalla.testutil.FakeRoleRepository
import uz.mahalla.testutil.FakeUserProfileStore
import uz.mahalla.testutil.MainDispatcherRule

/** Анкета продавца (issue #84): заявка на регистрацию заведения. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderFormViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val provider = FakeProviderRepository()

    @Test
    fun `phone and city are prefilled from what the app already knows`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = viewModel(
            phone = "+998901234567",
            city = City.BUKHARA,
        )

        advanceUntilIdle()

        // Набирать заново уже введённое — самый быстрый способ получить
        // брошенную форму.
        assertEquals("901234567", viewModel.state.value.form.phoneDigits)
        assertEquals(City.BUKHARA, viewModel.state.value.form.city)
    }

    @Test
    fun `errors stay hidden until the first attempt to submit`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onEvent(ProviderFormEvent.NameChanged("O"))
        advanceUntilIdle()

        assertTrue(viewModel.state.value.errors.isNotEmpty())
        assertTrue(viewModel.state.value.visibleErrors.isEmpty())
    }

    @Test
    fun `incomplete application never leaves the screen`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onEvent(ProviderFormEvent.SubmitClicked)
        advanceUntilIdle()

        assertTrue(ProviderFormError.NameRequired in viewModel.state.value.visibleErrors)
        assertTrue(ProviderFormError.CategoryRequired in viewModel.state.value.visibleErrors)
        assertTrue(provider.submitted.isEmpty())
    }

    @Test
    fun `accepted application turns into a confirmation, not a silent exit`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = viewModel(phone = "+998901234567")
        advanceUntilIdle()
        fill(viewModel)

        viewModel.onEvent(ProviderFormEvent.SubmitClicked)
        advanceUntilIdle()

        val form = provider.submitted.single()
        assertEquals("Osh Markazi", form.name)
        assertEquals(PlaceCategory.Food, form.category)
        assertEquals("901234567", form.phoneDigits)
        // Заведение уходит на модерацию — человек должен об этом узнать.
        assertEquals(PlaceModerationStatus.Pending, viewModel.state.value.registered?.status)
        assertFalse(viewModel.state.value.submitting)
    }

    @Test
    fun `the screen closes only after the confirmation is read`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = viewModel(phone = "+998901234567")
        advanceUntilIdle()
        fill(viewModel)
        viewModel.onEvent(ProviderFormEvent.SubmitClicked)
        advanceUntilIdle()

        viewModel.onEvent(ProviderFormEvent.DoneClicked)

        assertEquals(ProviderFormEffect.Finished, viewModel.effects.first())
    }

    @Test
    fun `server refusal keeps the filled form and shows its own text`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        provider.result = ApiResult.Failure(
            ApiFailure(error = ApiError.Forbidden, server = null),
        )
        val viewModel = viewModel(phone = "+998901234567")
        advanceUntilIdle()
        fill(viewModel)

        viewModel.onEvent(ProviderFormEvent.SubmitClicked)
        advanceUntilIdle()

        assertNull(viewModel.state.value.registered)
        assertEquals(ApiError.Forbidden, viewModel.state.value.submitError?.error)
        // Заново набирать заявку из-за отказа сервера человек не должен.
        assertEquals("Osh Markazi", viewModel.state.value.form.name)
    }

    @Test
    fun `editing clears the previous refusal`() = runTest(mainDispatcherRule.dispatcher) {
        provider.result = ApiResult.Failure(ApiFailure(error = ApiError.NoConnection, server = null))
        val viewModel = viewModel(phone = "+998901234567")
        advanceUntilIdle()
        fill(viewModel)
        viewModel.onEvent(ProviderFormEvent.SubmitClicked)
        advanceUntilIdle()

        viewModel.onEvent(ProviderFormEvent.DescriptionChanged("Osh va lagman"))

        assertNull(viewModel.state.value.submitError)
    }

    @Test
    fun `second tap does not send a second application`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = viewModel(phone = "+998901234567")
        advanceUntilIdle()
        fill(viewModel)

        viewModel.onEvent(ProviderFormEvent.SubmitClicked)
        viewModel.onEvent(ProviderFormEvent.SubmitClicked)
        advanceUntilIdle()

        assertEquals(1, provider.submitted.size)
    }

    @Test
    fun `active place is confirmed too`() = runTest(mainDispatcherRule.dispatcher) {
        provider.result = ApiResult.Success(
            RegisteredPlace(id = "p-9", name = "Osh Markazi", status = PlaceModerationStatus.Active),
        )
        val viewModel = viewModel(phone = "+998901234567")
        advanceUntilIdle()
        fill(viewModel)

        viewModel.onEvent(ProviderFormEvent.SubmitClicked)
        advanceUntilIdle()

        assertEquals(PlaceModerationStatus.Active, viewModel.state.value.registered?.status)
    }

    private fun fill(viewModel: ProviderFormViewModel) {
        viewModel.onEvent(ProviderFormEvent.NameChanged("Osh Markazi"))
        viewModel.onEvent(ProviderFormEvent.CategorySelected(PlaceCategory.Food))
        viewModel.onEvent(ProviderFormEvent.CitySelected(City.TASHKENT))
        viewModel.onEvent(ProviderFormEvent.AddressChanged("Chilonzor, 12-kvartal"))
    }

    private fun viewModel(
        phone: String? = null,
        city: City? = null,
    ) = ProviderFormViewModel(
        providerRepository = provider,
        roleRepository = FakeRoleRepository(RoleProfile(customer = CustomerForm(city = city))),
        profileStore = FakeUserProfileStore(UserProfile(phone = phone)),
        phoneValidator = PhoneNumberValidator(),
    )
}
