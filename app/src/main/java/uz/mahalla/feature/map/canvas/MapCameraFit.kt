package uz.mahalla.feature.map.canvas

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Камера карты: куда смотреть и с каким зумом (эпик 4.2).
 *
 * Считается здесь, а не средствами SDK, по двум причинам: MapKit умеет
 * подгонять камеру только под уже созданные объекты на живой карте (в тесте не
 * проверить), и на первом кадре камера нужна до того, как маркеры добавлены —
 * иначе карта успевает мигнуть нулевым меридианом.
 */
object MapCameraFit {

    /** Дефолт до первой загрузки и при пустой выдаче — центр Ташкента. */
    val DEFAULT_TARGET = MapCoordinates(latitude = 41.311081, longitude = 69.240562)

    /** Городской масштаб: видно район целиком. */
    const val DEFAULT_ZOOM = 12f

    /** Один маркер: улица с домами, дальше приближать нечего. */
    const val SINGLE_MARKER_ZOOM = 16f

    /**
     * «Моё местоположение»: на шаг мельче одиночного маркера — дом различим, но
     * в кадр попадает и соседняя улица, иначе непонятно, куда идти.
     */
    const val FOCUS_ZOOM = 15f

    const val MIN_ZOOM = 3f
    const val MAX_ZOOM = 18f

    /** Шаг кнопок «+»/«−». */
    const val ZOOM_STEP = 1f

    /**
     * Доля экрана, оставленная под поля: маркер на самом краю полотна наполовину
     * срезан подписью и кнопками, поэтому вписываем в 80% ширины.
     */
    private const val VIEWPORT_FILL = 0.8

    /** Пренебрежимо малый разброс — точки практически в одном месте. */
    private const val MIN_SPAN_DEGREES = 1e-4

    val DEFAULT = MapCameraPosition(DEFAULT_TARGET, DEFAULT_ZOOM)

    /**
     * Камера, в которую попадают все [points].
     *
     * Пустой список — [fallback] (обычно последняя позиция камеры или город
     * пользователя): пустая выдача не повод уносить карту в океан.
     *
     * Меридиан 180° не обрабатывается — Узбекистан от него далеко, а честная
     * поддержка разрыва долготы стоит дороже, чем даёт.
     */
    fun fit(
        points: List<MapCoordinates>,
        fallback: MapCameraPosition = DEFAULT,
    ): MapCameraPosition {
        if (points.isEmpty()) return fallback
        if (points.size == 1) {
            return MapCameraPosition(points.first(), clampZoom(SINGLE_MARKER_ZOOM))
        }

        val minLatitude = points.minOf { it.latitude }
        val maxLatitude = points.maxOf { it.latitude }
        val minLongitude = points.minOf { it.longitude }
        val maxLongitude = points.maxOf { it.longitude }

        val center = MapCoordinates(
            latitude = (minLatitude + maxLatitude) / 2,
            longitude = (minLongitude + maxLongitude) / 2,
        )

        // Широта в проекции Меркатора «шире» долготы, но у карты и экран выше,
        // чем шире, — на масштабах города поправки взаимно гасятся, поэтому
        // берём больший из двух разбросов, приведя широту к градусам долготы.
        val span = max(abs(maxLongitude - minLongitude), abs(maxLatitude - minLatitude) * 2)
        if (span < MIN_SPAN_DEGREES) {
            return MapCameraPosition(center, clampZoom(SINGLE_MARKER_ZOOM))
        }

        // Зум MapKit: на zoom = z в ширину полотна укладывается 360 / 2^z градусов.
        val zoom = ln(360.0 * VIEWPORT_FILL / span) / ln(2.0)
        return MapCameraPosition(center, clampZoom(zoom.toFloat()))
    }

    fun zoomIn(position: MapCameraPosition): MapCameraPosition =
        position.copy(zoom = clampZoom(position.zoom + ZOOM_STEP))

    fun zoomOut(position: MapCameraPosition): MapCameraPosition =
        position.copy(zoom = clampZoom(position.zoom - ZOOM_STEP))

    /** Камера «моё местоположение»: зум не меняем, если и так близко. */
    fun focusOn(
        point: MapCoordinates,
        current: MapCameraPosition = DEFAULT,
    ): MapCameraPosition =
        MapCameraPosition(point, clampZoom(max(current.zoom, FOCUS_ZOOM)))

    fun clampZoom(zoom: Float): Float = min(MAX_ZOOM, max(MIN_ZOOM, zoom))

    /**
     * Стоит ли карта уже там, куда её просят.
     *
     * Сравнение с допуском, а не по `equals`: положение возвращает SDK после
     * анимации, и оно отличается от заказанного в последних знаках. По этому
     * ответу полотно решает, двигать карту или нет, — строгое сравнение
     * заставляло бы её дёргаться на каждой рекомпозиции.
     */
    fun isSamePosition(first: MapCameraPosition, second: MapCameraPosition): Boolean =
        abs(first.target.latitude - second.target.latitude) < POSITION_EPSILON_DEGREES &&
            abs(first.target.longitude - second.target.longitude) < POSITION_EPSILON_DEGREES &&
            abs(first.zoom - second.zoom) < ZOOM_EPSILON

    /** ~1 метр: меньше половины маркера, глазом такое смещение не видно. */
    private const val POSITION_EPSILON_DEGREES = 1e-5

    /** Зум меняется шагами не мельче 0.1 — сотой доли хватает с запасом. */
    private const val ZOOM_EPSILON = 0.01f
}
