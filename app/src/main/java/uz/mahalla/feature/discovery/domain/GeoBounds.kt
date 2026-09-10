package uz.mahalla.feature.discovery.domain

/**
 * Прямоугольник на карте — то, чем `GET places/map-bounds` описывает область
 * запроса (issue #168).
 *
 * Отдельный тип, а не четыре `Double` в параметрах: `minLat`/`minLng`/`maxLat`/
 * `maxLng` в вызове перепутать местами легко, компилятор такого не поймает, а
 * бэкенд на вывернутом прямоугольнике вернёт пустой список — то есть карту без
 * маркеров и без единой ошибки в логе.
 *
 * Меридиан 180° не поддерживается: Узбекистан от него далеко, и разрыв долготы
 * стоил бы дороже, чем даёт (та же оговорка у `MapCameraFit.fit`).
 */
data class GeoBounds(
    val minLatitude: Double,
    val minLongitude: Double,
    val maxLatitude: Double,
    val maxLongitude: Double,
)
