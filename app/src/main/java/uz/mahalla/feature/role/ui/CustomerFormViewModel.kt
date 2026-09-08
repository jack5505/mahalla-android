package uz.mahalla.feature.role.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.feature.role.data.RoleRepository
import uz.mahalla.feature.role.domain.CustomerForm
import uz.mahalla.feature.role.domain.CustomerFormValidator
import javax.inject.Inject

/**
 * Анкета покупателя (issue #84).
 *
 * Сохранённые значения читаются **один раз**, при открытии: подписка на
 * хранилище затирала бы то, что человек печатает прямо сейчас (город из
 * анкеты пишется в те же настройки, что и выбор на шаге геолокации).
 */
@HiltViewModel
class CustomerFormViewModel @Inject constructor(
    private val roleRepository: RoleRepository,
) : MviViewModel<CustomerFormState, CustomerFormEvent, CustomerFormEffect>(CustomerFormState()) {

    init {
        viewModelScope.launch {
            val saved = roleRepository.current().customer
            updateState { copy(form = saved).revalidated() }
        }
    }

    override fun onEvent(event: CustomerFormEvent) {
        when (event) {
            is CustomerFormEvent.NameChanged -> updateForm { copy(fullName = event.name) }
            is CustomerFormEvent.CitySelected -> updateForm { copy(city = event.city) }
            is CustomerFormEvent.AddressChanged -> updateForm { copy(address = event.address) }
            CustomerFormEvent.SubmitClicked -> submit()
        }
    }

    private fun updateForm(transform: CustomerForm.() -> CustomerForm) {
        // Правка стирает прошлый отказ хранилища: сообщение о неудаче поверх
        // изменённой формы относилось бы уже к другим данным.
        updateState { copy(form = form.transform(), storageFailed = false).revalidated() }
    }

    private fun CustomerFormState.revalidated(): CustomerFormState =
        copy(errors = CustomerFormValidator.validate(form))

    private fun submit() {
        val state = currentState.revalidated()
        if (state.errors.isNotEmpty()) {
            updateState { state.copy(validationShown = true) }
            return
        }
        if (state.saving) return

        updateState { state.copy(saving = true, storageFailed = false) }
        viewModelScope.launch {
            val saved = roleRepository.saveCustomer(state.form)
            updateState { copy(saving = false, storageFailed = !saved) }
            // Анкета, которая «сохранилась» и пропала после перезапуска, хуже
            // честного отказа: уходим с экрана только на успехе.
            if (saved) emitEffect(CustomerFormEffect.Saved)
        }
    }
}
