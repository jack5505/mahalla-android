package uz.mahalla.feature.onboarding.ui

import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.feature.onboarding.domain.City

/** Что показывает экран геолокации (3.6). */
enum class GeoStage {
    /** Объяснение, зачем нужно разрешение. */
    Explain,

    /** Разрешение не дали — выбираем город руками. */
    CityPicker,
}

data class GeoState(
    val stage: GeoStage = GeoStage.Explain,
    val cities: List<City> = City.entries,
    val selectedCity: City? = null,
    val busy: Boolean = false,
    /** Разрешение было запрошено и отклонено — объяснение больше не показываем. */
    val permissionDenied: Boolean = false,
) : UiState

sealed interface GeoEvent : UiEvent {
    data object AllowRequested : GeoEvent
    data class PermissionResult(val granted: Boolean) : GeoEvent

    /** «Выбрать город вручную» — без запроса разрешения. */
    data object ChooseCityRequested : GeoEvent
    data class CitySelected(val city: City) : GeoEvent
}

sealed interface GeoEffect : UiEffect {
    /** Запросить системное разрешение — контракт живёт в Compose-слое. */
    data object RequestLocationPermission : GeoEffect

    /** Онбординг закончен: дальше основной граф. */
    data object Finished : GeoEffect
}
