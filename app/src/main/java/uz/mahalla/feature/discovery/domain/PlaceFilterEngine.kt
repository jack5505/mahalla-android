package uz.mahalla.feature.discovery.domain

import java.util.Locale

/**
 * Фильтрация и сортировка выдачи (эпик 4.3).
 *
 * Чистые функции без корутин и Android-зависимостей: те же правила применяются
 * и к ответу сервера, и к кэшу Room, поэтому офлайн-выдача отличается от
 * онлайновой только полнотой данных, а не порядком и не составом.
 */
object PlaceFilterEngine {

    fun apply(places: List<Place>, filters: DiscoveryFilters): List<Place> =
        places.filter { matches(it, filters) }.sortedWith(comparator(filters))

    fun matches(place: Place, filters: DiscoveryFilters): Boolean {
        if (filters.categories.isNotEmpty() && place.category !in filters.categories) return false
        if (filters.openNowOnly && !place.isOpenNow) return false
        filters.maxDistanceMeters?.let { if (place.distanceMeters > it) return false }
        // Место без оценок не проходит порог по рейтингу: 0.0 — это «нет
        // данных», а не «очень плохо», и подставлять его под «от 3 звёзд»
        // нельзя ни в ту, ни в другую сторону.
        filters.minRating?.let { if (!place.hasRating || place.rating < it) return false }
        return matchesQuery(place, filters.query)
    }

    /** Запрос ищется по названию, адресу — регистр и лишние пробелы не важны. */
    fun matchesQuery(place: Place, query: String): Boolean {
        val needle = normalize(query)
        if (needle.isEmpty()) return true
        return normalize(place.name).contains(needle) ||
            normalize(place.address.orEmpty()).contains(needle)
    }

    /**
     * Сравнение для выбранной сортировки. Внутри каждого варианта добавлен
     * запасной ключ (расстояние, затем id): без него порядок одинаковых
     * элементов меняется между вызовами и список «прыгает» при обновлении.
     */
    fun comparator(filters: DiscoveryFilters): Comparator<Place> = when (filters.sort) {
        PlaceSort.Distance -> compareBy(Place::distanceMeters).thenBy(Place::id)

        PlaceSort.Rating -> compareByDescending(Place::rating)
            .thenByDescending(Place::reviewCount)
            .thenBy(Place::distanceMeters)
            .thenBy(Place::id)

        PlaceSort.Relevance -> compareBy<Place> { relevanceRank(it, filters.query) }
            .thenBy(Place::distanceMeters)
            .thenBy(Place::id)
    }

    /**
     * Ранг совпадения: точное название → начало названия → вхождение в
     * название → вхождение в адрес. При пустом запросе ранг у всех одинаковый,
     * и релевантность вырождается в сортировку по расстоянию — это и ожидается
     * на главной.
     */
    fun relevanceRank(place: Place, query: String): Int {
        val needle = normalize(query)
        if (needle.isEmpty()) return RANK_NEUTRAL
        val name = normalize(place.name)
        return when {
            name == needle -> RANK_EXACT
            name.startsWith(needle) -> RANK_PREFIX
            name.contains(needle) -> RANK_NAME
            normalize(place.address.orEmpty()).contains(needle) -> RANK_ADDRESS
            else -> RANK_NONE
        }
    }

    /**
     * Апостроф в узбекской латинице пишут по-разному (`o'zbek`, `oʻzbek`,
     * `o‘zbek`), и без нормализации поиск «choyxona oʻzbek» не находит место,
     * записанное через ASCII-апостроф.
     */
    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .map { if (it in APOSTROPHES) '\'' else it }
        .joinToString(separator = "")
        .trim()

    private val APOSTROPHES = charArrayOf('‘', '’', 'ʻ', 'ʼ', '´', '`')

    private const val RANK_EXACT = 0
    private const val RANK_PREFIX = 1
    private const val RANK_NAME = 2
    private const val RANK_ADDRESS = 3
    private const val RANK_NONE = 4

    /** Ранг при пустом запросе — общий для всех, порядок решает расстояние. */
    private const val RANK_NEUTRAL = 0
}
