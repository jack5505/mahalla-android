package uz.mahalla.feature.role.ui

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.map.domain.MapPoint
import uz.mahalla.feature.onboarding.domain.City
import uz.mahalla.feature.role.domain.ProviderForm
import uz.mahalla.feature.role.domain.ProviderFormError
import uz.mahalla.feature.role.domain.RegisteredPlace

/**
 * Анкета продавца (issue #84).
 *
 * @param registered заявка принята — экран показывает подтверждение вместо
 * формы: заведение уходит на модерацию, и человек должен узнать об этом, а не
 * оказаться обратно в списке с ощущением, что ничего не произошло.
 * @param submitError отказ бэкенда: показывается текстом сервера рядом с
 * кнопкой (issue #34), а форма остаётся заполненной.
 */
data class ProviderFormState(
    val form: ProviderForm = ProviderForm(),
    val categories: List<PlaceCategory> = PlaceCategory.selectable,
    val cities: List<City> = City.entries,
    val errors: List<ProviderFormError> = emptyList(),
    val validationShown: Boolean = false,
    val submitting: Boolean = false,
    val submitError: ApiFailure? = null,
    val registered: RegisteredPlace? = null,
) : UiState {

    val visibleErrors: List<ProviderFormError>
        get() = if (validationShown) errors else emptyList()

    fun error(predicate: (ProviderFormError) -> Boolean): ProviderFormError? =
        visibleErrors.firstOrNull(predicate)
}

sealed interface ProviderFormEvent : UiEvent {
    data class NameChanged(val name: String) : ProviderFormEvent
    data class CategorySelected(val category: PlaceCategory) : ProviderFormEvent
    data class CitySelected(val city: City) : ProviderFormEvent
    data class AddressChanged(val address: String) : ProviderFormEvent
    data class PhoneChanged(val digits: String) : ProviderFormEvent
    data class DescriptionChanged(val description: String) : ProviderFormEvent
    data class WebsiteChanged(val website: String) : ProviderFormEvent

    /** «Выбрать на карте» (issue #90) — дальше решает граф. */
    data object PickLocationClicked : ProviderFormEvent

    /** Точка вернулась с карты. */
    data class LocationPicked(val point: MapPoint) : ProviderFormEvent

    data object SubmitClicked : ProviderFormEvent

    /** «Готово» на экране подтверждения. */
    data object DoneClicked : ProviderFormEvent
}

sealed interface ProviderFormEffect : UiEffect {
    /** Заявка отправлена и подтверждение прочитано. Дальше решает граф. */
    data object Finished : ProviderFormEffect

    /**
     * Открыть карту выбора точки (issue #90). [point] — то, что выбрано
     * сейчас: карта начинается с него, а не с города, иначе правка точки
     * означала бы искать своё заведение заново.
     */
    data class OpenMapPicker(val point: MapPoint?) : ProviderFormEffect
}
