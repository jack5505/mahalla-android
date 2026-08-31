package uz.mahalla.feature.services.ui.order

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.data.prefs.UserProfileStore
import uz.mahalla.feature.services.data.ServicesRepository
import uz.mahalla.feature.services.domain.ServiceOrderForm
import uz.mahalla.feature.services.domain.ServiceOrderValidator
import uz.mahalla.navigation.ServiceOrderRoute
import javax.inject.Inject

/**
 * Заказ услуги (issue #71): имя, услуга — и заявка уходит мастеру.
 *
 * Имя подставляется из профиля аккаунта, но остаётся редактируемым: услугу
 * заказывают и для другого человека. Профиль не приехал — поле просто пустое,
 * форму это не блокирует.
 */
@HiltViewModel
class ServiceOrderViewModel @Inject constructor(
    private val repository: ServicesRepository,
    private val profileStore: UserProfileStore,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<ServiceOrderState, ServiceOrderEvent, ServiceOrderEffect>(ServiceOrderState()) {

    private val route: ServiceOrderRoute = savedStateHandle.toRoute()

    init {
        updateState { copy(placeId = route.placeId, placeName = route.placeName) }
        viewModelScope.launch {
            val name = profileStore.current().fullName?.trim().orEmpty()
            // Подставляем только в нетронутое поле: ответ хранилища может
            // приехать позже первого ввода, и затирать набранное им нельзя.
            if (name.isNotEmpty() && currentState.form.customerName.isEmpty()) {
                updateForm { copy(customerName = name) }
            }
        }
    }

    override fun onEvent(event: ServiceOrderEvent) {
        when (event) {
            is ServiceOrderEvent.NameChanged -> updateForm { copy(customerName = event.name) }
            is ServiceOrderEvent.ServiceChanged -> updateForm { copy(serviceName = event.service) }
            ServiceOrderEvent.SubmitClicked -> submit()

            // Форма чистая, но имя переписывать заново незачем — оно то же.
            ServiceOrderEvent.NewOrderRequested -> updateState {
                copy(
                    request = null,
                    submitFailure = null,
                    validationShown = false,
                    form = form.copy(serviceName = ""),
                ).revalidated()
            }

            ServiceOrderEvent.BackClicked -> emitEffect(ServiceOrderEffect.NavigateBack)
        }
    }

    private fun updateForm(transform: ServiceOrderForm.() -> ServiceOrderForm) {
        updateState { copy(form = form.transform(), submitFailure = null).revalidated() }
    }

    private fun ServiceOrderState.revalidated(): ServiceOrderState =
        copy(errors = ServiceOrderValidator.validate(form))

    private fun submit() {
        val state = currentState
        if (state.isSubmitting) return
        if (state.errors.isNotEmpty()) {
            // Ошибки уже посчитаны — нажатие только делает их видимыми.
            updateState { copy(validationShown = true) }
            return
        }
        updateState { copy(isSubmitting = true, submitFailure = null, validationShown = true) }
        viewModelScope.launch {
            when (val result = repository.sendServiceOrder(state.placeId, state.form)) {
                is ApiResult.Success -> updateState {
                    copy(isSubmitting = false, request = result.data)
                }

                is ApiResult.Failure -> updateState {
                    copy(isSubmitting = false, submitFailure = result.failure)
                }
            }
        }
    }
}
