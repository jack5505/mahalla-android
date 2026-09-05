package uz.mahalla.feature.map.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.discovery.data.CatalogRepository
import uz.mahalla.feature.discovery.domain.DiscoveryFilters
import uz.mahalla.feature.discovery.domain.Place
import uz.mahalla.feature.map.canvas.MapCameraFit
import uz.mahalla.feature.map.canvas.MapCoordinates
import uz.mahalla.feature.map.canvas.MapMarkerUi
import uz.mahalla.feature.map.data.MapKitInitializer
import uz.mahalla.feature.map.data.MapKitKeyStore
import uz.mahalla.feature.map.data.UserLocationProvider
import javax.inject.Inject

/**
 * Карта (issue #65): маркеры, камера, выбор места, «моё местоположение».
 *
 * ViewModel не знает про Yandex MapKit: она отдаёт [MapMarkerUi] и
 * [uz.mahalla.feature.map.canvas.MapCameraPosition], а переводит их в примитивы
 * SDK полотно `MapCanvas`. Единственное исключение — [mapInitializer]: движок
 * поднимается лениво, а композиция не должна ходить в Hilt сама, поэтому ворота
 * инициализации приезжают на экран через ViewModel (так же это описано в KDoc
 * `MapCanvas`).
 */
@HiltViewModel
class MapViewModel @Inject constructor(
    private val repository: CatalogRepository,
    private val locationProvider: UserLocationProvider,
    /** Передаётся экрану как есть — сама ViewModel SDK не трогает. */
    val mapInitializer: MapKitInitializer,
    /**
     * Тем же путём и по той же причине, что [mapInitializer]: ключ вводится в
     * шторке поверх объяснения «карты нет» (issue #129), а состояния у этого
     * ввода ровно столько, сколько живёт шторка.
     */
    val mapKeyStore: MapKitKeyStore,
) : MviViewModel<MapState, MapEvent, MapEffect>(MapState()) {

    /**
     * Координаты за время жизни экрана уже искали. Поле, а не часть состояния:
     * это память о сделанном, а не то, что рисуется. Нужно затем, чтобы возврат
     * на экран (`ON_RESUME` приходит на каждом) не запускал поиск заново.
     */
    private var locateRequested = false

    init {
        load()
    }

    override fun onEvent(event: MapEvent) {
        when (event) {
            MapEvent.Retry -> load()

            is MapEvent.MarkerClicked -> onMarkerClicked(event.placeId)

            MapEvent.SelectionCleared -> select(null)

            is MapEvent.CameraMoved -> updateState { copy(camera = event.camera) }

            MapEvent.ZoomInClicked -> updateState { copy(camera = MapCameraFit.zoomIn(camera)) }

            MapEvent.ZoomOutClicked -> updateState { copy(camera = MapCameraFit.zoomOut(camera)) }

            MapEvent.MyLocationClicked -> onMyLocationClicked()

            is MapEvent.LocationPermissionChecked -> onPermissionChecked(event.granted)

            is MapEvent.LocationPermissionResult -> onPermissionResult(event.granted)

            MapEvent.NoticeDismissed -> updateState { copy(locationNotice = null) }

            is MapEvent.PlaceClicked -> emitEffect(MapEffect.OpenPlace(event.placeId))
        }
    }

    private fun load() {
        updateState { copy(places = ScreenState.Loading, markers = emptyList()) }
        viewModelScope.launch {
            when (val result = repository.places(DiscoveryFilters())) {
                is ApiResult.Failure -> updateState {
                    copy(places = ScreenState.Error(result.failure))
                }

                is ApiResult.Success -> {
                    // На карту попадают только места с координатами: место без
                    // точки нарисовать негде, а в счётчике маркеров оно
                    // соврало бы.
                    val mappable = result.data.items.filter { it.point != null }
                    val loaded = markersOf(mappable, selectedId = null)
                    updateState {
                        copy(
                            places = if (mappable.isEmpty()) {
                                ScreenState.Empty
                            } else {
                                ScreenState.Content(mappable)
                            },
                            markers = loaded,
                            // Камера подгоняется под выдачу, а пустая выдача
                            // оставляет её там, где карта уже стоит: уносить
                            // экран в дефолтный город на каждом обновлении —
                            // потеря того, что пользователь только что нашёл.
                            camera = MapCameraFit.fit(
                                points = loaded.map(MapMarkerUi::point),
                                fallback = camera,
                            ),
                            selectedPlaceId = null,
                        )
                    }
                }
            }
        }
    }

    private fun onMarkerClicked(placeId: String) {
        // Неизвестный id — маркер из прошлой выдачи: полотно могло отдать тап,
        // пока приезжал новый список.
        if (currentState.markers.none { it.id == placeId }) return
        // Камеру при выборе не двигаем: пользователь ткнул в то, что видит, а
        // самопроизвольный полёт под пальцем читается как промах.
        select(placeId)
    }

    private fun select(placeId: String?) {
        if (currentState.selectedPlaceId == placeId) return
        updateState {
            copy(
                selectedPlaceId = placeId,
                markers = markers.map { it.copy(selected = it.id == placeId) },
            )
        }
    }

    private fun onMyLocationClicked() {
        updateState { copy(locationNotice = null) }
        if (currentState.showUserLocation) {
            locate()
        } else {
            emitEffect(MapEffect.RequestLocationPermission)
        }
    }

    /**
     * Разрешение, выданное раньше (онбординг 3.6 или настройки устройства).
     *
     * Координаты спрашиваются сразу, не дожидаясь тапа: человек, разрешивший
     * геолокацию, ждёт увидеть на карте себя, а не центр Ташкента — тем более
     * когда каталог ничего не нашёл и подгонять камеру не подо что (issue #126).
     * Молча: об этой попытке он не просил, и плашка «не удалось определить»
     * поверх карты была бы ответом на незаданный вопрос.
     */
    private fun onPermissionChecked(granted: Boolean) {
        updateState { copy(showUserLocation = granted) }
        if (granted && !locateRequested) locate(silent = true)
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
     * Координаты спрашивает [UserLocationProvider]: сперва MapKit, потом
     * системный `LocationManager` (issue #126). Отсутствие координат — норма, но
     * молча оставлять карту на месте нельзя: тап без последствий выглядит как
     * поломка кнопки.
     *
     * [silent] — попытка, которую пользователь не запрашивал (разрешение уже
     * было выдано при открытии экрана): она не показывает отказ и не отбирает
     * камеру у найденных мест — маркеры человек уже видит, уходить с них он не
     * просил.
     */
    private fun locate(silent: Boolean = false) {
        if (currentState.isLocating) return
        locateRequested = true
        updateState { copy(isLocating = true) }
        viewModelScope.launch {
            val point = locationProvider.currentLocation()
            updateState {
                copy(
                    isLocating = false,
                    camera = when {
                        point == null -> camera
                        silent && markers.isNotEmpty() -> camera
                        else -> MapCameraFit.focusOn(point, camera)
                    },
                    locationNotice = when {
                        point != null -> null
                        silent -> locationNotice
                        else -> LocationNotice.Unavailable
                    },
                )
            }
        }
    }

    private fun markersOf(places: List<Place>, selectedId: String?): List<MapMarkerUi> =
        places.mapNotNull { place ->
            val point = place.point ?: return@mapNotNull null
            MapMarkerUi(
                id = place.id,
                point = MapCoordinates(point.latitude, point.longitude),
                title = place.name,
                selected = place.id == selectedId,
            )
        }
}
