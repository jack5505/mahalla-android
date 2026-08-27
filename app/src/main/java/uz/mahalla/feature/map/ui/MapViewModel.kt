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
import uz.mahalla.feature.map.domain.MarkerClusterer
import javax.inject.Inject

/**
 * Карта (эпик 4.2): маркеры, кластеризация, выбор по тапу, «моё
 * местоположение».
 *
 * SDK карты ещё не выбран (блокер эпика), поэтому вся логика здесь оперирует
 * координатами, а не типами Yandex/Google: подключение SDK не должно трогать
 * ViewModel.
 */
@HiltViewModel
class MapViewModel @Inject constructor(
    private val repository: CatalogRepository,
) : MviViewModel<MapState, MapEvent, MapEffect>(MapState()) {

    init {
        load()
    }

    override fun onEvent(event: MapEvent) {
        when (event) {
            MapEvent.Retry -> load()

            is MapEvent.ZoomChanged -> onZoomChanged(event.zoom)

            is MapEvent.ClusterClicked -> onClusterClicked(event.clusterId)

            MapEvent.SelectionCleared -> updateState { copy(selectedClusterId = null) }

            // Разрешение и получение координат — дело экрана и системного
            // диалога; ViewModel только просит.
            MapEvent.MyLocationClicked -> emitEffect(MapEffect.RequestLocation)

            is MapEvent.PlaceClicked -> emitEffect(MapEffect.OpenPlace(event.placeId))
        }
    }

    private fun load() {
        updateState { copy(places = ScreenState.Loading, clusters = emptyList()) }
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
                    updateState {
                        copy(
                            places = if (mappable.isEmpty()) {
                                ScreenState.Empty
                            } else {
                                ScreenState.Content(mappable)
                            },
                            clusters = MarkerClusterer.cluster(mappable, zoom),
                            selectedClusterId = null,
                        )
                    }
                }
            }
        }
    }

    private fun onZoomChanged(zoom: Int) {
        val clamped = zoom.coerceIn(MapState.MIN_ZOOM, MapState.MAX_ZOOM)
        if (clamped == currentState.zoom) return
        val places = (currentState.places as? ScreenState.Content)?.data.orEmpty()
        updateState {
            copy(
                zoom = clamped,
                clusters = MarkerClusterer.cluster(places, clamped),
                // Кластеры пересобрались — прежний id мог исчезнуть, и
                // раскрытая карточка осталась бы висеть без своего маркера.
                selectedClusterId = null,
            )
        }
    }

    private fun onClusterClicked(clusterId: String) {
        val cluster = currentState.clusters.firstOrNull { it.id == clusterId } ?: return
        val single: Place? = cluster.single
        if (single != null) {
            updateState { copy(selectedClusterId = clusterId, camera = cluster.center) }
            emitEffect(MapEffect.MoveCamera(cluster.center, currentState.zoom))
            return
        }
        // Тап по группе — приблизиться к ней, а не открыть случайное место.
        // Выделение при этом не ставим: после пересборки кластеров этого id
        // может уже не существовать.
        val zoom = (currentState.zoom + ZOOM_STEP).coerceAtMost(MapState.MAX_ZOOM)
        onZoomChanged(zoom)
        updateState { copy(camera = cluster.center) }
        emitEffect(MapEffect.MoveCamera(cluster.center, currentState.zoom))
    }

    private companion object {
        /** На сколько приближает тап по кластеру. */
        const val ZOOM_STEP = 2
    }
}
