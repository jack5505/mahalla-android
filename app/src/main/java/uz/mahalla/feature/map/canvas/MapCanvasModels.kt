package uz.mahalla.feature.map.canvas

/**
 * Модели полотна карты (эпик 4.2).
 *
 * Слой намеренно не знает ни про домен discovery, ни про типы MapKit: экран
 * карты переводит свои маркеры в [MapMarkerUi], полотно — в примитивы SDK.
 * Из-за этого вся геометрия ниже проверяется обычными JVM-тестами, а замена
 * SDK не расходится по экранам.
 */
data class MapCoordinates(
    val latitude: Double,
    val longitude: Double,
)

/** Положение камеры: центр и зум MapKit (целое значение ≈ вдвое ближе). */
data class MapCameraPosition(
    val target: MapCoordinates,
    val zoom: Float,
)

/** Маркер на карте. [id] — ключ диффа и то, что уезжает в `onMarkerClick`. */
data class MapMarkerUi(
    val id: String,
    val point: MapCoordinates,
    val title: String = "",
    val selected: Boolean = false,
)
