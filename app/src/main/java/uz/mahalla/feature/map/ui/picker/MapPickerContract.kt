package uz.mahalla.feature.map.ui.picker

import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.feature.map.canvas.MapCameraFit
import uz.mahalla.feature.map.canvas.MapCameraPosition
import uz.mahalla.feature.map.domain.MapPoint
import uz.mahalla.feature.map.ui.LocationNotice

/**
 * Выбор точки на карте (issue #90).
 *
 * Точку выбирает не тап по карте, а сама карта: метка нарисована неподвижно в
 * центре экрана, а человек двигает под ней тайлы. Поэтому отдельного поля
 * «выбранная точка» в состоянии нет — это всегда центр [camera]. Так проще
 * попасть в нужный дом одним пальцем и не надо гадать, куда именно пришёлся
 * тап: под пальцем точка не видна.
 *
 * @param resolvingStart начальная позиция ещё ищется (последняя известная
 * позиция устройства или город). Пока она не найдена, подтверждать нечего:
 * человек подтвердил бы центр Ташкента, которого не выбирал.
 */
data class MapPickerState(
    val camera: MapCameraPosition = MapCameraFit.DEFAULT,
    val resolvingStart: Boolean = true,
    /** Слой «моё местоположение»: включается только с выданным разрешением. */
    val showUserLocation: Boolean = false,
    /** Идёт определение координат — кнопка «моё местоположение» занята. */
    val isLocating: Boolean = false,
    /** Почему «моё местоположение» ничего не дало: молчать здесь нельзя. */
    val locationNotice: LocationNotice? = null,
) : UiState {

    /** Точка под меткой в центре экрана. */
    val point: MapPoint?
        get() = if (resolvingStart) {
            null
        } else {
            MapPoint.of(camera.target.latitude, camera.target.longitude)
        }

    val canConfirm: Boolean get() = point != null
}

sealed interface MapPickerEvent : UiEvent {
    /** Карту подвинул пользователь — состояние догоняет полотно. */
    data class CameraMoved(val camera: MapCameraPosition) : MapPickerEvent

    data object ZoomInClicked : MapPickerEvent
    data object ZoomOutClicked : MapPickerEvent

    data object MyLocationClicked : MapPickerEvent

    /** Разрешение, уже выданное системе, — экран сообщает его при открытии. */
    data class LocationPermissionChecked(val granted: Boolean) : MapPickerEvent

    /** Ответ системного диалога. */
    data class LocationPermissionResult(val granted: Boolean) : MapPickerEvent

    data object NoticeDismissed : MapPickerEvent

    data object ConfirmClicked : MapPickerEvent
}

sealed interface MapPickerEffect : UiEffect {
    /** Точка выбрана — граф возвращает её экрану, который открыл карту. */
    data class Picked(val point: MapPoint) : MapPickerEffect

    /** Разрешение спрашивает экран — у ViewModel нет Activity. */
    data object RequestLocationPermission : MapPickerEffect
}
