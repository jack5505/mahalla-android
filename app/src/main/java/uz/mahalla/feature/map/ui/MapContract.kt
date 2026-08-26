package uz.mahalla.feature.map.ui

import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.discovery.domain.GeoPoint
import uz.mahalla.feature.discovery.domain.Place
import uz.mahalla.feature.map.domain.MapCluster
import uz.mahalla.feature.map.domain.MarkerClusterer

/**
 * Состояние карты (эпик 4.2).
 *
 * Модель полностью независима от картографического SDK: выбор между Yandex
 * MapKit и Google Maps — открытый блокер эпика, и когда он закроется, менять
 * придётся только слой отрисовки.
 */
data class MapState(
    val places: ScreenState<List<Place>> = ScreenState.Loading,
    val clusters: List<MapCluster> = emptyList(),
    val zoom: Int = DEFAULT_ZOOM,
    val camera: GeoPoint = TASHKENT_CENTER,
    /** Раскрытый по тапу кластер: одна метка — карточка, группа — список. */
    val selectedClusterId: String? = null,
) : UiState {

    val selectedCluster: MapCluster?
        get() = clusters.firstOrNull { it.id == selectedClusterId }

    val markerCount: Int get() = clusters.sumOf(MapCluster::size)

    companion object {
        /** Городской зум: район видно целиком, кластеры ещё осмысленны. */
        const val DEFAULT_ZOOM = 12
        const val MIN_ZOOM = 4
        val MAX_ZOOM = MarkerClusterer.MAX_CLUSTER_ZOOM + 2

        /** Пока геолокация не получена, карта смотрит на центр Ташкента. */
        val TASHKENT_CENTER = GeoPoint(latitude = 41.3111, longitude = 69.2797)
    }
}

sealed interface MapEvent : UiEvent {
    data object Retry : MapEvent
    data class ZoomChanged(val zoom: Int) : MapEvent
    data class ClusterClicked(val clusterId: String) : MapEvent
    data object SelectionCleared : MapEvent
    data object MyLocationClicked : MapEvent
    data class PlaceClicked(val placeId: String) : MapEvent
}

sealed interface MapEffect : UiEffect {
    data class OpenPlace(val placeId: String) : MapEffect

    /** Камера — эффект, а не состояние: её нельзя переигрывать при рекомпозиции. */
    data class MoveCamera(val target: GeoPoint, val zoom: Int) : MapEffect

    /** Разрешение на геолокацию спрашивает экран — у него есть Activity. */
    data object RequestLocation : MapEffect
}
