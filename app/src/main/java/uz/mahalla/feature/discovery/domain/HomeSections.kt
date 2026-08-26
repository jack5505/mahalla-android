package uz.mahalla.feature.discovery.domain

/**
 * Раскладка главной (эпик 4.1): из одной выдачи собираются блоки «рядом» и
 * «рекомендации».
 *
 * Логика вынесена из ViewModel в чистую функцию — правило «что считать
 * рекомендацией» проверяется тестом, а не рассматриванием экрана.
 */
object HomeSections {

    /** Сколько карточек влезает в горизонтальный блок макета. */
    const val SECTION_LIMIT = 6

    /** Ниже этого рейтинга место не попадает в рекомендации самостоятельно. */
    const val RECOMMENDED_MIN_RATING = 4.5

    /** И меньше этого числа отзывов — тоже: одна пятёрка ещё не репутация. */
    const val RECOMMENDED_MIN_REVIEWS = 10

    fun nearby(places: List<Place>, limit: Int = SECTION_LIMIT): List<Place> = places
        .sortedWith(PlaceFilterEngine.comparator(DiscoveryFilters(sort = PlaceSort.Distance)))
        .take(limit)

    /**
     * Рекомендации: то, что сервер пометил `isRecommended`, плюс места с
     * высоким рейтингом и достаточным числом отзывов. Порядок — по рейтингу,
     * пометка сервера идёт первой.
     */
    fun recommended(places: List<Place>, limit: Int = SECTION_LIMIT): List<Place> = places
        .filter { it.isRecommended || isHighlyRated(it) }
        .sortedWith(
            compareByDescending(Place::isRecommended)
                .thenByDescending(Place::rating)
                .thenByDescending(Place::reviewCount)
                .thenBy(Place::id),
        )
        .take(limit)

    private fun isHighlyRated(place: Place): Boolean =
        place.hasRating &&
            place.rating >= RECOMMENDED_MIN_RATING &&
            place.reviewCount >= RECOMMENDED_MIN_REVIEWS
}
