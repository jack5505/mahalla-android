package uz.mahalla.feature.role.ui

import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.feature.onboarding.domain.City
import uz.mahalla.feature.role.domain.CustomerForm
import uz.mahalla.feature.role.domain.CustomerFormError

/**
 * Анкета покупателя (issue #84).
 *
 * @param errors считаются на каждое изменение, а показываются только после
 * первой попытки сохранить ([validationShown]): подсвечивать «имя обязательно»
 * человеку, который ещё не дописал первую букву, — это ругать за незаконченный
 * ввод.
 * @param storageFailed запись не удалась. Отдельно от ошибок валидации: тут
 * человек всё сделал правильно, а не сработало хранилище.
 */
data class CustomerFormState(
    val form: CustomerForm = CustomerForm(),
    val cities: List<City> = City.entries,
    val errors: List<CustomerFormError> = emptyList(),
    val validationShown: Boolean = false,
    val saving: Boolean = false,
    val storageFailed: Boolean = false,
) : UiState {

    /** Ошибки полей показываем только после попытки сохранить. */
    val visibleErrors: List<CustomerFormError>
        get() = if (validationShown) errors else emptyList()

    fun error(predicate: (CustomerFormError) -> Boolean): CustomerFormError? =
        visibleErrors.firstOrNull(predicate)
}

sealed interface CustomerFormEvent : UiEvent {
    data class NameChanged(val name: String) : CustomerFormEvent
    data class CitySelected(val city: City) : CustomerFormEvent
    data class AddressChanged(val address: String) : CustomerFormEvent
    data object SubmitClicked : CustomerFormEvent
}

sealed interface CustomerFormEffect : UiEffect {
    /** Анкета сохранена. Куда идти дальше, решает граф. */
    data object Saved : CustomerFormEffect
}
