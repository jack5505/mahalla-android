package uz.mahalla.feature.map.domain

import androidx.compose.runtime.Immutable
import uz.mahalla.feature.discovery.domain.GeoPoint
import uz.mahalla.feature.discovery.domain.Place
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * Кластер маркеров: либо одно место, либо группа.
 *
 * [places] хранится целиком, а не только счётчиком: по тапу на кластер нужно
 * показать, что именно в него попало, и второй запрос за этим делать незачем.
 */
@Immutable
data class MapCluster(
    val id: String,
    val center: GeoPoint,
    val places: List<Place>,
) {
    val size: Int get() = places.size
    val isSingle: Boolean get() = places.size == 1
    val single: Place? get() = places.singleOrNull()
}

/**
 * Кластеризация маркеров (эпик 4.2).
 *
 * Реализация намеренно не зависит от картографического SDK: выбор между
 * Yandex MapKit и Google Maps ещё не сделан (блокер эпика), а группировка
 * точек от SDK не зависит и должна пережить это решение без переписывания.
 *
 * Метод — сетка по координатам: точки раскладываются по ячейкам, размер
 * ячейки падает вдвое на каждый уровень зума. Сетка выбрана вместо
 * расстояний между всеми парами (O(n²)) — на выдаче в сотни точек разница
 * заметна, а визуально результат тот же.
 */
object MarkerClusterer {

    /** Размер ячейки на нулевом зуме, в градусах. */
    const val BASE_CELL_DEGREES = 8.0

    /** Дальше этого зума кластеры не нужны — показываем отдельные метки. */
    const val MAX_CLUSTER_ZOOM = 16

    fun cluster(places: List<Place>, zoom: Int): List<MapCluster> {
        val withPoint = places.filter { it.point != null }
        if (withPoint.isEmpty()) return emptyList()
        if (zoom >= MAX_CLUSTER_ZOOM) return withPoint.map(::singleCluster)

        val cell = cellSizeDegrees(zoom)
        return withPoint
            .groupBy { place -> cellKey(place.point!!, cell) }
            // Порядок ячеек фиксируем: иначе маркеры пересобираются в разном
            // порядке и карта перерисовывает их без причины.
            .toSortedMap(compareBy({ it.first }, { it.second }))
            .map { (key, group) -> MapCluster(id = clusterId(key), center = center(group), places = group) }
    }

    /** Ячейка вдвое меньше на каждый шаг зума, но не мельче предела double. */
    fun cellSizeDegrees(zoom: Int): Double =
        BASE_CELL_DEGREES / 2.0.pow(zoom.coerceIn(0, MAX_CLUSTER_ZOOM))

    /**
     * Центр кластера — среднее по точкам. Через антимеридиан (долготы вида
     * `179` и `-179`) усреднение сломалось бы, но Узбекистан от него далеко,
     * так что случай сознательно не поддерживается.
     */
    fun center(places: List<Place>): GeoPoint {
        val points = places.mapNotNull(Place::point)
        return GeoPoint(
            latitude = points.sumOf(GeoPoint::latitude) / points.size,
            longitude = points.sumOf(GeoPoint::longitude) / points.size,
        )
    }

    private fun singleCluster(place: Place): MapCluster =
        MapCluster(id = "p:${place.id}", center = place.point!!, places = listOf(place))

    private fun cellKey(point: GeoPoint, cell: Double): Pair<Long, Long> = Pair(
        floor(point.latitude / cell).toLong(),
        floor(point.longitude / cell).toLong(),
    )

    private fun clusterId(key: Pair<Long, Long>): String = "c:${key.first}:${key.second}"

    /** Расстояние между точками в градусах — нужно тестам и отладке сетки. */
    fun chebyshevDegrees(a: GeoPoint, b: GeoPoint): Double =
        maxOf(abs(a.latitude - b.latitude), abs(a.longitude - b.longitude))
}
