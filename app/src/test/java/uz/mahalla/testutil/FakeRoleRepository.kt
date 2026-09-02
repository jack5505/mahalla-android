package uz.mahalla.testutil

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import uz.mahalla.feature.role.data.RoleProfile
import uz.mahalla.feature.role.data.RoleRepository
import uz.mahalla.feature.role.domain.CustomerForm
import uz.mahalla.feature.role.domain.UserRole

/**
 * Роль и анкета покупателя в памяти (issue #84): экраны проверяются без
 * DataStore и Robolectric.
 *
 * [writeFailure] воспроизводит недоступное хранилище — форма обязана сказать
 * об этом, а не сделать вид, что сохранилась.
 */
class FakeRoleRepository(initial: RoleProfile = RoleProfile()) : RoleRepository {

    private val state = MutableStateFlow(initial)

    var writeFailure: Boolean = false

    val selectedRoles = mutableListOf<UserRole>()
    val savedForms = mutableListOf<CustomerForm>()

    override val profile: Flow<RoleProfile> = state

    override suspend fun current(): RoleProfile = state.value

    override suspend fun selectRole(role: UserRole) {
        selectedRoles += role
        if (!writeFailure) state.value = state.value.copy(role = role)
    }

    override suspend fun saveCustomer(form: CustomerForm): Boolean {
        savedForms += form
        if (writeFailure) return false
        state.value = RoleProfile(role = UserRole.Customer, customer = form.trimmed())
        return true
    }
}
