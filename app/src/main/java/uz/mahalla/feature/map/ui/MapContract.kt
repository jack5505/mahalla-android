package uz.mahalla.feature.map.ui

import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.discovery.domain.Place
import uz.mahalla.feature.map.canvas.MapCameraFit
import uz.mahalla.feature.map.canvas.MapCameraPosition
import uz.mahalla.feature.map.canvas.MapMarkerUi

/**
 * Состояние карты (issue #65).
 *
 * Модель говорит на языке полотна ([MapMarkerUi], [MapCameraPosition]), а не
 * Yandex MapKit: слой `canvas` от SDK изолирован, поэтому ViewModel и её тесты
 * переживут замену движка карты.
 *
 * Кластеризацию состояние не считает: её делает сам MapKit
 * (`ClusterizedPlacemarkCollection`) и пересобирает на каждом зуме — сеточный
 * `MarkerClusterer` эпика 4 умел это только для одного заранее известного зума
 * и вместе с подключением полотна удалён.
 */
data class MapState(
    val places: ScreenState<List<Place>> = ScreenState.Loading,
    /** То, что нарисовано на полотне: место без координат сюда не попадает. */
    val markers: List<MapMarkerUi> = emptyList(),
    val camera: MapCameraPosition = MapCameraFit.DEFAULT,
    /** Раскрытый по тапу маркер — карточка места поверх карты. */
    val selectedPlaceId: String? = null,
    /** Слой «моё местоположение»: включается только с выданным разрешением. */
    val showUserLocation: Boolean = false,
    /** Идёт определение координат — кнопка «моё местоположение» занята. */
    val isLocating: Boolean = false,
    /**
     * Почему «моё местоположение» ничего не дало. Молчать здесь нельзя: тап по
     * кнопке, после которого карта не двинулась, читается как поломка.
     */
    val locationNotice: LocationNotice? = null,
) : UiState {

    val selectedPlace: Place?
        get() = selectedPlaceId?.let { id -> loadedPlaces.firstOrNull { it.id == id } }

    val markerCount: Int get() = markers.size

    private val loadedPlaces: List<Place>
        get() = (places as? ScreenState.Content)?.data.orEmpty()
}

/** Отказ геолокации словами: разрешения нет либо координат не дождались. */
enum class LocationNotice {
    PermissionDenied,
    Unavailable,
}

sealed interface MapEvent : UiEvent {
    data object Retry : MapEvent

    /** Тап по маркеру на полотне. */
    data class MarkerClicked(val placeId: String) : MapEvent

    data object SelectionCleared : MapEvent

    /** Карту подвинул пользователь — состояние догоняет полотно. */
    data class CameraMoved(val camera: MapCameraPosition) : MapEvent

    data object ZoomInClicked : MapEvent
    data object ZoomOutClicked : MapEvent

    data object MyLocationClicked : MapEvent

    /** Разрешение, уже выданное системе, — экран сообщает его при открытии. */
    data class LocationPermissionChecked(val granted: Boolean) : MapEvent

    /** Ответ системного диалога. */
    data class LocationPermissionResult(val granted: Boolean) : MapEvent

    data object NoticeDismissed : MapEvent

    /** Тап по карточке раскрытого места. */
    data class PlaceClicked(val placeId: String) : MapEvent
}

sealed interface MapEffect : UiEffect {
    data class OpenPlace(val placeId: String) : MapEffect

    /** Разрешение спрашивает экран — у ViewModel нет Activity. */
    data object RequestLocationPermission : MapEffect
}
