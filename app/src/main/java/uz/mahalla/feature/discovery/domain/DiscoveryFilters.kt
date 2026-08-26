package uz.mahalla.feature.discovery.domain

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import uz.mahalla.R

/** Порядок выдачи (эпик 4.3). */
enum class PlaceSort(val apiValue: String, @StringRes val labelRes: Int) {
    /** По совпадению с запросом, при пустом запросе — по расстоянию. */
    Relevance("relevance", R.string.sort_relevance),
    Distance("distance", R.string.sort_distance),
    Rating("rating", R.string.sort_rating),
}

/**
 * Набор фильтров выдачи (эпик 4.3). Иммутабельный и сериализуемый по смыслу:
 * одно и то же значение уходит и в запрос к серверу, и в локальную фильтрацию
 * кэша — иначе офлайн и онлайн показывали бы разное.
 */
@Immutable
data class DiscoveryFilters(
    val query: String = "",
    val categories: Set<PlaceCategory> = emptySet(),
    /** `null` — без ограничения по расстоянию. */
    val maxDistanceMeters: Int? = null,
    /** `null` — без ограничения по рейтингу. */
    val minRating: Double? = null,
    val openNowOnly: Boolean = false,
    val sort: PlaceSort = PlaceSort.Relevance,
) {
    /**
     * Сколько фильтров показывать бейджем на кнопке. Запрос и сортировка не
     * считаются: запрос виден в строке поиска, сортировка есть всегда.
     */
    val activeCount: Int
        get() = categories.size +
            (if (maxDistanceMeters != null) 1 else 0) +
            (if (minRating != null) 1 else 0) +
            (if (openNowOnly) 1 else 0)

    val isDefault: Boolean get() = activeCount == 0 && sort == PlaceSort.Relevance

    /**
     * Запрос без единого ограничения — «покажи всё, что рядом». Только такой
     * ответ годится в офлайн-кэш: срез поиска по слову там сделал бы
     * офлайн-главную выдачей вчерашнего запроса. Сортировка на это не влияет —
     * кэш это набор, а не порядок.
     */
    val isUnfiltered: Boolean get() = activeCount == 0 && query.isBlank()

    /** Сброс не трогает запрос: «очистить фильтры» — не «очистить поиск». */
    fun cleared(): DiscoveryFilters = DiscoveryFilters(query = query)

    fun toggleCategory(category: PlaceCategory): DiscoveryFilters = copy(
        categories = if (category in categories) categories - category else categories + category,
    )

    /**
     * Один параметр `category` на запрос: серверный контракт не принимает
     * список. При нескольких выбранных категориях сервер фильтрует по первой,
     * остальное досекает [PlaceFilterEngine] локально.
     */
    fun apiCategory(): String? = categories
        .sortedBy(PlaceCategory::ordinal)
        .firstNotNullOfOrNull(PlaceCategory.Companion::apiValueOrNull)

    companion object {
        /** Пресеты радиуса из макета фильтров. */
        val distancePresetsMeters: List<Int> = listOf(500, 1_000, 3_000, 5_000)

        /** Пресеты рейтинга из макета фильтров. */
        val ratingPresets: List<Double> = listOf(3.0, 4.0, 4.5)
    }
}
