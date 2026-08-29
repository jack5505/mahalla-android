package uz.mahalla.feature.discovery.domain

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Расстояние между двумя точками (issue #53).
 *
 * Нужно там, где его не посчитал сервер: ответ поиска (`GET /search`) отдаёт
 * только координаты места, а карточка в списке показывает «за сколько метров».
 * Без пересчёта у всей поисковой выдачи стояло бы «0 м», и сортировка по
 * расстоянию перестала бы что-либо значить.
 *
 * Формула гаверсинуса на сфере: на городских расстояниях её погрешность
 * (~0.3%) меньше, чем разница между дверью заведения и точкой на карте.
 */
object GeoDistance {

    /** Средний радиус Земли, метры. */
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun meters(from: GeoPoint, to: GeoPoint): Int {
        val fromLatitude = Math.toRadians(from.latitude)
        val toLatitude = Math.toRadians(to.latitude)
        val deltaLatitude = toLatitude - fromLatitude
        val deltaLongitude = Math.toRadians(to.longitude - from.longitude)

        val haversine = sin(deltaLatitude / 2) * sin(deltaLatitude / 2) +
            cos(fromLatitude) * cos(toLatitude) * sin(deltaLongitude / 2) * sin(deltaLongitude / 2)
        // min(1.0, …) страхует от выхода за область определения asin на
        // погрешности округления у диаметрально противоположных точек.
        val central = 2 * asin(min(1.0, sqrt(haversine)))

        return (EARTH_RADIUS_METERS * central).toInt()
    }
}
