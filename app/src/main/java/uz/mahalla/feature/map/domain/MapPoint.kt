package uz.mahalla.feature.map.domain

import java.util.Locale

/**
 * Точка, выбранная человеком на карте (issue #90).
 *
 * Отдельный тип, а не пара `Double`: точку возят между экраном выбора,
 * анкетой и запросом, и перепутанные местами широта с долготой в паре
 * аргументов — ошибка, которую компилятор не поймает, а карта покажет
 * заведение в Индийском океане.
 *
 * Координаты проверяются на входе ([of]): значения вне диапазона Земли и
 * `NaN` не точка, а признак того, что что-то посчитано неверно, — отправлять
 * такое на бэкенд незачем.
 */
data class MapPoint(
    val latitude: Double,
    val longitude: Double,
) {

    /**
     * Точка строкой для аргумента маршрута: типизированные маршруты
     * Navigation кладут аргументы в `Bundle`, и для пары дробных чисел
     * пришлось бы заводить собственный `NavType` — та же причина, по которой
     * канал доставки кода едет именем константы (`OtpRoute.channel`).
     *
     * Формат — `Locale.ROOT`: на русской локали устройства `%f` дал бы
     * `41,311081`, и разделитель полей совпал бы с разделителем дробной части
     * (та же грабля, что у `GeoHeaderInterceptor`, issue #53).
     */
    fun encode(): String = "${format(latitude)}$SEPARATOR${format(longitude)}"

    /** Координаты человеку: их сверяют глазами, поэтому без сокращений. */
    fun formatted(): String = "${format(latitude)}, ${format(longitude)}"

    companion object {
        /**
         * Шесть знаков после запятой — около 11 см на экваторе. Больше не
         * нужно: точность самой карты и пальца заметно грубее.
         */
        const val PRECISION = 6

        private const val SEPARATOR = ","

        fun of(latitude: Double, longitude: Double): MapPoint? {
            if (!latitude.isFinite() || !longitude.isFinite()) return null
            if (latitude !in MIN_LATITUDE..MAX_LATITUDE) return null
            if (longitude !in MIN_LONGITUDE..MAX_LONGITUDE) return null
            return MapPoint(latitude, longitude)
        }

        /**
         * Разбор аргумента маршрута. Мусор — `null`, а не исключение: строку
         * кладёт в маршрут само приложение, но пережить смерть процесса и
         * приехать испорченной она может.
         */
        fun decode(raw: String?): MapPoint? {
            val parts = raw?.trim()?.split(SEPARATOR) ?: return null
            if (parts.size != 2) return null
            val latitude = parts[0].trim().toDoubleOrNull() ?: return null
            val longitude = parts[1].trim().toDoubleOrNull() ?: return null
            return of(latitude, longitude)
        }

        private const val MIN_LATITUDE = -90.0
        private const val MAX_LATITUDE = 90.0
        private const val MIN_LONGITUDE = -180.0
        private const val MAX_LONGITUDE = 180.0

        private fun format(value: Double): String =
            String.format(Locale.ROOT, "%.${PRECISION}f", value)
    }
}
