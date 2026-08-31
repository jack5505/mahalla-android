package uz.mahalla.feature.services.ui.order

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.feature.services.domain.ServiceOrderError
import uz.mahalla.feature.services.domain.ServiceOrderForm
import uz.mahalla.feature.services.domain.ServiceOrderValidator
import uz.mahalla.feature.services.domain.ServiceRequest

/**
 * Форма заказа услуги (issue #71).
 *
 * [errors] пересчитываются валидатором при каждом изменении формы и лежат в
 * состоянии, а не считаются в composable: доступность кнопки — правило, а не
 * деталь вёрстки (тот же приём, что в checkout'е еды).
 *
 * [validationShown] отделяет «форма ещё не заполнена» от «человек нажал и
 * ошибся»: краснеть авансом на пустом поле не за что.
 *
 * [request] — заявка, которую вернул сервер. Пока она `null`, экран показывает
 * форму; как только приехала — состояние заявки, потому что «отправлено» это
 * ещё не «мастер согласился».
 */
data class ServiceOrderState(
    val placeId: String = "",
    val placeName: String = "",
    val form: ServiceOrderForm = ServiceOrderForm(),
    val errors: List<ServiceOrderError> = ServiceOrderValidator.validate(ServiceOrderForm()),
    val validationShown: Boolean = false,
    val isSubmitting: Boolean = false,
    val submitFailure: ApiFailure? = null,
    val request: ServiceRequest? = null,
) : UiState {

    val canSubmit: Boolean get() = errors.isEmpty() && !isSubmitting

    private val visibleErrors: List<ServiceOrderError>
        get() = if (validationShown) errors else emptyList()

    fun error(predicate: (ServiceOrderError) -> Boolean): ServiceOrderError? =
        visibleErrors.firstOrNull(predicate)
}

sealed interface ServiceOrderEvent : UiEvent {
    data class NameChanged(val name: String) : ServiceOrderEvent
    data class ServiceChanged(val service: String) : ServiceOrderEvent
    data object SubmitClicked : ServiceOrderEvent

    /** «Заказать ещё раз» — возврат к пустой форме после отказа мастера. */
    data object NewOrderRequested : ServiceOrderEvent

    data object BackClicked : ServiceOrderEvent
}

sealed interface ServiceOrderEffect : UiEffect {
    data object NavigateBack : ServiceOrderEffect
}
