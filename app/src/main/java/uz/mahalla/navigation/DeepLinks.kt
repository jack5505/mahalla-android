package uz.mahalla.navigation

/**
 * Deep links (эпик 1.2). Схема `mahalla://` объявлена в манифесте
 * `MainActivity`; placeholder'ы в шаблонах обязаны совпадать с именами полей
 * соответствующих маршрутов из [Routes.kt] — это проверяет
 * `RoutesSerializationTest`.
 *
 * С эпика 11 по этим же ссылкам приложение открывает пуш: у уведомления нет
 * доступа к `NavController`, оно умеет только отдать системе `Intent` с URI, а
 * разбирает его тот же граф навигации. Отсюда правило: **ссылка добавляется
 * сюда только вместе с `navDeepLink` в `MahallaNavHost`** — иначе пуш открывал
 * бы приложение на главной, и человек решил бы, что нажатие не сработало.
 */
object DeepLinks {

    const val SCHEME = "mahalla"

    /** Карточка заведения: `mahalla://place/{placeId}`. */
    const val PLACE_PATTERN = "$SCHEME://place/{placeId}"

    /** Статус заказа еды: `mahalla://order/{orderId}` (эпик 11). */
    const val ORDER_PATTERN = "$SCHEME://order/{orderId}"

    /**
     * Центр уведомлений: `mahalla://notifications` (эпик 11).
     *
     * Запасная цель для всякого пуша, у которого своего экрана нет: список
     * уведомлений — это место, где уведомление точно есть, поэтому нажатие
     * всегда что-то открывает.
     */
    const val NOTIFICATIONS_PATTERN = "$SCHEME://notifications"

    /** Подписка: `mahalla://subscription` (эпик 11). Без аргументов — какая
     *  подписка, бэкенд знает сам. */
    const val SUBSCRIPTION_PATTERN = "$SCHEME://subscription"

    fun place(placeId: String): String = "$SCHEME://place/$placeId"

    fun order(orderId: String): String = "$SCHEME://order/$orderId"

    fun notifications(): String = NOTIFICATIONS_PATTERN

    fun subscription(): String = SUBSCRIPTION_PATTERN
}
