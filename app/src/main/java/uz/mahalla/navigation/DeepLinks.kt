package uz.mahalla.navigation

/**
 * Deep links (эпик 1.2). Схема `mahalla://` объявлена в манифесте
 * `MainActivity`; placeholder'ы в шаблонах обязаны совпадать с именами полей
 * соответствующих маршрутов из [Routes.kt] — это проверяет
 * `RoutesSerializationTest`.
 */
object DeepLinks {

    const val SCHEME = "mahalla"

    /** Карточка заведения: `mahalla://place/{placeId}`. */
    const val PLACE_PATTERN = "$SCHEME://place/{placeId}"

    fun place(placeId: String): String = "$SCHEME://place/$placeId"
}
