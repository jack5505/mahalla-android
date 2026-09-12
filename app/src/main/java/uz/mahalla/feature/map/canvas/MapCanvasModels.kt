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

/**
 * Видимая область карты: юго-западный и северо-восточный углы (issue #168).
 *
 * По ней грузятся маркеры (`GET places/map-bounds`), поэтому область приходит
 * от полотна, а не считается по камере: центр с зумом не знают ни размера
 * полотна, ни его пропорций, и вычисленный по ним прямоугольник расходился бы
 * с тем, что человек видит, — как раз по краям, где маркеров и не хватало.
 *
 * Меридиан 180° не поддерживается — см. оговорку в [MapCameraFit.fit].
 */
data class MapBounds(
    val southWest: MapCoordinates,
    val northEast: MapCoordinates,
) {

    /**
     * Область, по которой можно идти в сеть. Вывернутый, бесконечный или
     * стянутый в точку прямоугольник — не «пустая карта», а признак того, что
     * что-то посчитано неверно: запрос по нему вернул бы пустой список, и это
     * выглядело бы как «рядом ничего нет».
     *
     * Вырожденная область — не выдуманный случай: `visibleRegion`, прочитанный
     * до того, как окно карты промерено, отдаёт все четыре угла в одной точке
     * (обычно `0, 0`), и запрос ушёл бы в Гвинейский залив.
     */
    val isValid: Boolean
        get() = southWest.latitude.isFinite() && southWest.longitude.isFinite() &&
            northEast.latitude.isFinite() && northEast.longitude.isFinite() &&
            northEast.latitude - southWest.latitude >= MIN_SPAN_DEGREES &&
            northEast.longitude - southWest.longitude >= MIN_SPAN_DEGREES &&
            southWest.latitude >= MIN_LATITUDE && northEast.latitude <= MAX_LATITUDE &&
            southWest.longitude >= MIN_LONGITUDE && northEast.longitude <= MAX_LONGITUDE

    /** Лежит ли [other] целиком внутри: по этому ответу решается, идти ли в сеть. */
    fun contains(other: MapBounds): Boolean =
        other.southWest.latitude >= southWest.latitude &&
            other.southWest.longitude >= southWest.longitude &&
            other.northEast.latitude <= northEast.latitude &&
            other.northEast.longitude <= northEast.longitude

    /**
     * Область с запасом в [fraction] от своего размера с каждой стороны.
     *
     * Запас — не украшение: маркеры грузятся чуть шире кадра, поэтому короткий
     * сдвиг карты пальцем показывает уже загруженное вместо того, чтобы каждый
     * раз ходить в сеть.
     */
    fun expandedBy(fraction: Double): MapBounds {
        val latitudePadding = (northEast.latitude - southWest.latitude) * fraction
        val longitudePadding = (northEast.longitude - southWest.longitude) * fraction
        return MapBounds(
            southWest = MapCoordinates(
                latitude = (southWest.latitude - latitudePadding).coerceAtLeast(MIN_LATITUDE),
                longitude = (southWest.longitude - longitudePadding).coerceAtLeast(MIN_LONGITUDE),
            ),
            northEast = MapCoordinates(
                latitude = (northEast.latitude + latitudePadding).coerceAtMost(MAX_LATITUDE),
                longitude = (northEast.longitude + longitudePadding).coerceAtMost(MAX_LONGITUDE),
            ),
        )
    }

    private companion object {
        const val MIN_LATITUDE = -90.0
        const val MAX_LATITUDE = 90.0
        const val MIN_LONGITUDE = -180.0
        const val MAX_LONGITUDE = 180.0

        /**
         * Пренебрежимо малый разброс: кадр мельче этого — не кадр, а точка (то
         * же значение, что у `MapCameraFit.MIN_SPAN_DEGREES`, ~11 м).
         */
        const val MIN_SPAN_DEGREES = 1e-4
    }
}

/** Маркер на карте. [id] — ключ диффа и то, что уезжает в `onMarkerClick`. */
data class MapMarkerUi(
    val id: String,
    val point: MapCoordinates,
    val title: String = "",
    val selected: Boolean = false,
)
