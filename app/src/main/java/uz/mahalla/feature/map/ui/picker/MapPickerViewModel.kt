package uz.mahalla.feature.map.ui.picker

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.data.location.RequestLocationProvider
import uz.mahalla.feature.map.canvas.MapCameraFit
import uz.mahalla.feature.map.canvas.MapCameraPosition
import uz.mahalla.feature.map.canvas.MapCoordinates
import uz.mahalla.feature.map.data.MapKitInitializer
import uz.mahalla.feature.map.data.UserLocationProvider
import uz.mahalla.feature.map.domain.MapPoint
import uz.mahalla.feature.map.ui.LocationNotice
import uz.mahalla.navigation.MapPickerArgs
import javax.inject.Inject

/**
 * Выбор точки на карте (issue #90).
 *
 * Как и на экране карты, ViewModel не знает про Yandex MapKit: она отдаёт
 * положение камеры, а переводит его в примитивы SDK полотно `MapCanvas`.
 * Единственное исключение — [mapInitializer]: движок поднимается лениво, а
 * композиция не должна ходить в Hilt сама.
 *
 * Начальная позиция ищется по убыванию точности: точка, выбранная в прошлый
 * раз (аргумент маршрута) → последняя известная позиция устройства или центр
 * города ([RequestLocationProvider] — та же лестница, что у запросов
 * авторизации). Свежие координаты у MapKit здесь не спрашиваются: это
 * ожидание фикса на экране, который человек открыл, чтобы двигать карту
 * руками. Для этого есть отдельная кнопка «моё местоположение».
 */
@HiltViewModel
class MapPickerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val locationProvider: UserLocationProvider,
    private val requestLocationProvider: RequestLocationProvider,
    /** Передаётся экрану как есть — сама ViewModel SDK не трогает. */
    val mapInitializer: MapKitInitializer,
) : MviViewModel<MapPickerState, MapPickerEvent, MapPickerEffect>(MapPickerState()) {

    init {
        val picked = MapPoint.decode(savedStateHandle[MapPickerArgs.POINT])
        if (picked == null) {
            resolveStart()
        } else {
            updateState {
                copy(
                    camera = MapCameraPosition(picked.toCoordinates(), MapCameraFit.SINGLE_MARKER_ZOOM),
                    resolvingStart = false,
                )
            }
        }
    }

    override fun onEvent(event: MapPickerEvent) {
        when (event) {
            // Жест пользователя старше поиска начальной позиции: карту он уже
            // двигает сам, и увести её из-под пальца было бы промахом.
            is MapPickerEvent.CameraMoved -> updateState {
                copy(camera = event.camera, resolvingStart = false)
            }

            MapPickerEvent.ZoomInClicked -> updateState {
                copy(camera = MapCameraFit.zoomIn(camera))
            }

            MapPickerEvent.ZoomOutClicked -> updateState {
                copy(camera = MapCameraFit.zoomOut(camera))
            }

            MapPickerEvent.MyLocationClicked -> onMyLocationClicked()

            is MapPickerEvent.LocationPermissionChecked -> updateState {
                copy(showUserLocation = event.granted)
            }

            is MapPickerEvent.LocationPermissionResult -> onPermissionResult(event.granted)

            MapPickerEvent.NoticeDismissed -> updateState { copy(locationNotice = null) }

            MapPickerEvent.ConfirmClicked -> currentState.point?.let { point ->
                emitEffect(MapPickerEffect.Picked(point))
            }
        }
    }

    private fun resolveStart() {
        viewModelScope.launch {
            val start = requestLocationProvider.current()
            updateState {
                // Пользователь мог начать двигать карту раньше, чем приехал
                // ответ: тогда начальная позиция уже не нужна.
                if (!resolvingStart) {
                    this
                } else {
                    copy(
                        camera = MapCameraPosition(
                            target = MapCoordinates(start.latitude, start.longitude),
                            zoom = MapCameraFit.FOCUS_ZOOM,
                        ),
                        resolvingStart = false,
                    )
                }
            }
        }
    }

    private fun onMyLocationClicked() {
        updateState { copy(locationNotice = null) }
        if (currentState.showUserLocation) {
            locate()
        } else {
            emitEffect(MapPickerEffect.RequestLocationPermission)
        }
    }

    private fun onPermissionResult(granted: Boolean) {
        updateState {
            copy(
                showUserLocation = granted,
                locationNotice = if (granted) null else LocationNotice.PermissionDenied,
            )
        }
        if (granted) locate()
    }

    /**
     * Координаты спрашиваются у MapKit (см. [UserLocationProvider]).
     * Отсутствие координат — норма, но молча оставлять карту на месте нельзя:
     * тап без последствий выглядит как поломка кнопки.
     */
    private fun locate() {
        if (currentState.isLocating) return
        updateState { copy(isLocating = true) }
        viewModelScope.launch {
            val point = locationProvider.currentLocation()
            updateState {
                copy(
                    isLocating = false,
                    camera = if (point == null) camera else MapCameraFit.focusOn(point, camera),
                    // Позицию нашли — начальная больше не нужна, и метка в
                    // центре стоит там, куда человек только что прилетел.
                    resolvingStart = if (point == null) resolvingStart else false,
                    locationNotice = if (point == null) LocationNotice.Unavailable else null,
                )
            }
        }
    }
}

private fun MapPoint.toCoordinates() = MapCoordinates(latitude, longitude)
