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
import uz.mahalla.feature.onboarding.domain.City
import uz.mahalla.feature.role.data.RoleProfile
import uz.mahalla.feature.role.domain.CustomerForm
import uz.mahalla.feature.role.domain.UserRole
import uz.mahalla.testutil.FakeRoleRepository
import uz.mahalla.testutil.MainDispatcherRule

/** Выбор роли (issue #84): покупатель или продавец, и дальше — своя анкета. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoleViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `nothing is selected until the person chooses`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val viewModel = RoleViewModel(FakeRoleRepository())

        advanceUntilIdle()

        // «Покупатель» по умолчанию был бы враньём про выбор, которого не было.
        assertNull(viewModel.state.value.selected)
        assertFalse(viewModel.state.value.canContinue)
    }

    @Test
    fun `saved role and a filled form are shown on the screen`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val repository = FakeRoleRepository(
            RoleProfile(
                role = UserRole.Customer,
                customer = CustomerForm(fullName = "Jahongir", city = City.TASHKENT),
            ),
        )
        val viewModel = RoleViewModel(repository)

        advanceUntilIdle()

        assertEquals(UserRole.Customer, viewModel.state.value.selected)
        assertEquals(UserRole.Customer, viewModel.state.value.saved)
        assertTrue(viewModel.state.value.customerFilled)
    }

    @Test
    fun `a fresh choice is not overwritten by what is stored`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val repository = FakeRoleRepository(RoleProfile(role = UserRole.Customer))
        val viewModel = RoleViewModel(repository)
        advanceUntilIdle()

        // Экран открывают и для того, чтобы роль сменить.
        viewModel.onEvent(RoleEvent.RoleSelected(UserRole.Provider))
        advanceUntilIdle()

        assertEquals(UserRole.Provider, viewModel.state.value.selected)
    }

    @Test
    fun `continue stores the choice and opens the matching form`() = runTest(
        mainDispatcherRule.dispatcher,
    ) {
        val repository = FakeRoleRepository()
        val viewModel = RoleViewModel(repository)

        viewModel.onEvent(RoleEvent.RoleSelected(UserRole.Provider))
        viewModel.onEvent(RoleEvent.ContinueClicked)
        advanceUntilIdle()

        // Выбор запоминается до анкеты: человек может закрыть форму на
        // полпути, и спрашивать «кто вы» второй раз незачем.
        assertEquals(listOf(UserRole.Provider), repository.selectedRoles)
        assertEquals(RoleEffect.OpenProviderForm, viewModel.effects.first())
        assertFalse(viewModel.state.value.busy)
    }

    @Test
    fun `customer choice opens the customer form`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = RoleViewModel(FakeRoleRepository())

        viewModel.onEvent(RoleEvent.RoleSelected(UserRole.Customer))
        viewModel.onEvent(RoleEvent.ContinueClicked)
        advanceUntilIdle()

        assertEquals(RoleEffect.OpenCustomerForm, viewModel.effects.first())
    }

    @Test
    fun `continue without a choice does nothing`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRoleRepository()
        val viewModel = RoleViewModel(repository)

        viewModel.onEvent(RoleEvent.ContinueClicked)
        advanceUntilIdle()

        assertTrue(repository.selectedRoles.isEmpty())
    }

    @Test
    fun `storage refusal still lets the form open`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRoleRepository().apply { writeFailure = true }
        val viewModel = RoleViewModel(repository)

        viewModel.onEvent(RoleEvent.RoleSelected(UserRole.Customer))
        viewModel.onEvent(RoleEvent.ContinueClicked)
        advanceUntilIdle()

        // Запирать человека на выборе роли из-за настройки нельзя: анкета
        // сохранит себя сама и сама скажет о неудаче.
        assertEquals(RoleEffect.OpenCustomerForm, viewModel.effects.first())
    }
}
