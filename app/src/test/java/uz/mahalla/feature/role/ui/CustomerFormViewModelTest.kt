package uz.mahalla.feature.role.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.feature.onboarding.domain.City
import uz.mahalla.feature.role.data.RoleProfile
import uz.mahalla.feature.role.domain.CustomerForm
import uz.mahalla.feature.role.domain.CustomerFormError
import uz.mahalla.testutil.FakeRoleRepository
import uz.mahalla.testutil.MainDispatcherRule

/** Анкета покупателя (issue #84): имя, город, адрес по умолчанию. */
@OptIn(ExperimentalCoroutinesApi::class)
class CustomerFormViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `saved answers are shown in the form`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRoleRepository(
            RoleProfile(
                customer = CustomerForm(
                    fullName = "Jahongir",
                    city = City.SAMARKAND,
                    address = "Registon 4",
                ),
            ),
        )
        val viewModel = CustomerFormViewModel(repository)

        advanceUntilIdle()

        assertEquals("Jahongir", viewModel.state.value.form.fullName)
        assertEquals(City.SAMARKAND, viewModel.state.value.form.city)
        assertEquals("Registon 4", viewModel.state.value.form.address)
    }

    @Test
    fun `errors stay hidden until the first attempt to save`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = CustomerFormViewModel(FakeRoleRepository())
        advanceUntilIdle()

        viewModel.onEvent(CustomerFormEvent.NameChanged("J"))
        advanceUntilIdle()

        // Ругать за незаконченный ввод нельзя: ошибки уже посчитаны, но не
        // показаны.
        assertTrue(viewModel.state.value.errors.isNotEmpty())
        assertTrue(viewModel.state.value.visibleErrors.isEmpty())
    }

    @Test
    fun `saving an incomplete form shows what is missing and does not store it`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val repository = FakeRoleRepository()
        val viewModel = CustomerFormViewModel(repository)
        advanceUntilIdle()

        viewModel.onEvent(CustomerFormEvent.SubmitClicked)
        advanceUntilIdle()

        assertTrue(CustomerFormError.NameRequired in viewModel.state.value.visibleErrors)
        assertTrue(CustomerFormError.CityRequired in viewModel.state.value.visibleErrors)
        assertTrue(repository.savedForms.isEmpty())
    }

    @Test
    fun `a filled form is stored trimmed and closes the screen`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val repository = FakeRoleRepository()
        val viewModel = CustomerFormViewModel(repository)
        advanceUntilIdle()

        viewModel.onEvent(CustomerFormEvent.NameChanged("  Jahongir Sabirov  "))
        viewModel.onEvent(CustomerFormEvent.CitySelected(City.TASHKENT))
        viewModel.onEvent(CustomerFormEvent.AddressChanged(" Chilonzor 12 "))
        viewModel.onEvent(CustomerFormEvent.SubmitClicked)
        advanceUntilIdle()

        assertEquals(CustomerFormEffect.Saved, viewModel.effects.first())
        val saved = repository.current().customer
        assertEquals("Jahongir Sabirov", saved.fullName)
        assertEquals(City.TASHKENT, saved.city)
        assertEquals("Chilonzor 12", saved.address)
        assertFalse(viewModel.state.value.saving)
    }

    @Test
    fun `storage refusal keeps the person on the screen`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val repository = FakeRoleRepository().apply { writeFailure = true }
        val viewModel = CustomerFormViewModel(repository)
        advanceUntilIdle()
        val effects = mutableListOf<CustomerFormEffect>()
        backgroundScope.launch { viewModel.effects.toList(effects) }

        viewModel.onEvent(CustomerFormEvent.NameChanged("Jahongir"))
        viewModel.onEvent(CustomerFormEvent.CitySelected(City.TASHKENT))
        viewModel.onEvent(CustomerFormEvent.SubmitClicked)
        advanceUntilIdle()

        // Анкета, которая «сохранилась» и пропала после перезапуска, хуже
        // честного отказа.
        assertTrue(viewModel.state.value.storageFailed)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `editing clears the previous storage refusal`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val repository = FakeRoleRepository().apply { writeFailure = true }
        val viewModel = CustomerFormViewModel(repository)
        advanceUntilIdle()

        viewModel.onEvent(CustomerFormEvent.NameChanged("Jahongir"))
        viewModel.onEvent(CustomerFormEvent.CitySelected(City.TASHKENT))
        viewModel.onEvent(CustomerFormEvent.SubmitClicked)
        advanceUntilIdle()
        viewModel.onEvent(CustomerFormEvent.AddressChanged("Chilonzor 12"))

        assertFalse(viewModel.state.value.storageFailed)
    }
}
