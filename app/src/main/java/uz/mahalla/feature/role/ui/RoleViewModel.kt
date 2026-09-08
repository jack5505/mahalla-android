package uz.mahalla.feature.role.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.feature.role.data.RoleRepository
import uz.mahalla.feature.role.domain.UserRole
import javax.inject.Inject

/**
 * «Кто вы» (issue #84): покупатель или продавец.
 *
 * Выбор запоминается до заполнения анкеты — человек может закрыть форму на
 * полпути, и спрашивать его второй раз незачем. Отказ хранилища при этом не
 * запирает экран: анкета всё равно откроется, а сохранить её попытается уже
 * она сама и скажет о неудаче словами.
 */
@HiltViewModel
class RoleViewModel @Inject constructor(
    private val roleRepository: RoleRepository,
) : MviViewModel<RoleState, RoleEvent, RoleEffect>(RoleState()) {

    init {
        viewModelScope.launch {
            roleRepository.profile.collect { profile ->
                updateState {
                    copy(
                        // Уже сделанный выбор подставляется в экран, но не
                        // затирает касание: человек мог открыть экран, чтобы
                        // роль сменить.
                        selected = selected ?: profile.role,
                        saved = profile.role,
                        customerFilled = !profile.customer.isEmpty,
                    )
                }
            }
        }
    }

    override fun onEvent(event: RoleEvent) {
        when (event) {
            is RoleEvent.RoleSelected -> updateState { copy(selected = event.role) }
            RoleEvent.ContinueClicked -> continueToForm()
        }
    }

    private fun continueToForm() {
        val role = currentState.selected ?: return
        if (currentState.busy) return
        updateState { copy(busy = true) }
        viewModelScope.launch {
            roleRepository.selectRole(role)
            updateState { copy(busy = false) }
            emitEffect(
                when (role) {
                    UserRole.Customer -> RoleEffect.OpenCustomerForm
                    UserRole.Provider -> RoleEffect.OpenProviderForm
                },
            )
        }
    }
}
