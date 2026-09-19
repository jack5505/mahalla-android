package uz.mahalla.core.analytics

/**
 * Событие продуктовой аналитики **без обязательного заведения** (issue #226).
 *
 * `POST analytics/track` ([AnalyticsEvent]) требует `placeId` и закрытое
 * перечисление `eventType` — им не отправить открытие экрана без заведения,
 * поисковый запрос, шаг воронки до выбора заведения или отказ бэкенда.
 * Бэкенд завёл под это отдельную ручку `POST analytics/events`
 * (`docs/API-CONTRACT.md`, раздел AnalyticsApi): свободное имя события,
 * `placeId` необязателен, батч, `deviceId` вместо обязательного токена.
 *
 * Событие уходит через [AnalyticsEventQueue], а не напрямую: ручка требует
 * поле времени события и работает без сети хуже, чем с очередью на диске —
 * то, чего `track` не умел (`docs/adr/0006-analitika-bez-seti.md`).
 */
data class AnalyticsQueuedEvent(
    val name: String,
    val placeId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Экраны без заведения, для которых нет виднее места, чем открытие самого
 * экрана (issue #226): профиль, кошелёк, «мои активности», главная, онбординг.
 */
object AnalyticsScreens {
    const val HOME = "home"
    const val PROFILE = "profile"
    const val WALLET = "wallet"
    const val ACTIVITIES = "activities"
    const val ONBOARDING = "onboarding"
}

/**
 * Фабрика событий без заведения — единственное место, где заводится
 * [AnalyticsQueuedEvent], по тому же принципу, что и [AnalyticsEvents]: имя
 * события не разбегается по экранам как строка.
 */
object AnalyticsQueuedEvents {

    private const val NAME_SCREEN_VIEW = "screen_view"
    private const val NAME_SEARCH = "search"
    private const val NAME_VERTICAL_OPENED = "funnel.vertical_opened"
    private const val NAME_ORDER_REJECTED = "order_rejected"
    private const val NAME_BOOKING_REJECTED = "booking_rejected"

    private const val METADATA_SCREEN = "screen"
    private const val METADATA_QUERY = "query"
    private const val METADATA_VERTICAL = "vertical"
    private const val METADATA_CODE = "code"

    /** Экран без заведения открылся — см. [AnalyticsScreens]. */
    fun screenOpened(screen: String): AnalyticsQueuedEvent =
        AnalyticsQueuedEvent(name = NAME_SCREEN_VIEW, metadata = mapOf(METADATA_SCREEN to screen))

    /** Поисковый запрос выполнен (по нажатию «Найти», а не на каждый символ). */
    fun searched(query: String): AnalyticsQueuedEvent =
        AnalyticsQueuedEvent(name = NAME_SEARCH, metadata = mapOf(METADATA_QUERY to query))

    /**
     * Открыта вертикаль — шаг воронки до выбора заведения.
     *
     * Берёт голую строку, а не [AnalyticsVertical]: категории каталога на
     * главной (`PlaceCategory`) — семь **других** значений (есть аптека и
     * мастер, нет очереди и записи отдельно от больницы), это разные
     * классификации одного бизнеса, а не одна и та же вертикаль под двумя
     * именами. Натягивать one на другое значило бы врать в панели о том, что
     * на самом деле нажали.
     */
    fun verticalOpened(vertical: String): AnalyticsQueuedEvent =
        AnalyticsQueuedEvent(
            name = NAME_VERTICAL_OPENED,
            metadata = mapOf(METADATA_VERTICAL to vertical),
        )

    /**
     * Сервер отклонил оформление заказа бизнес-кодом (`OUT_OF_STOCK` и т. п.) —
     * пара к [AnalyticsEvents.ordered]: та же точка воронки, но отказ, а не успех.
     */
    fun orderRejected(placeId: String, code: String): AnalyticsQueuedEvent =
        AnalyticsQueuedEvent(
            name = NAME_ORDER_REJECTED,
            placeId = placeId,
            metadata = mapOf(METADATA_CODE to code),
        )

    /** Пара к [AnalyticsEvents.booked]: сервер отклонил запись/бронь/талон/билет. */
    fun bookingRejected(placeId: String, code: String): AnalyticsQueuedEvent =
        AnalyticsQueuedEvent(
            name = NAME_BOOKING_REJECTED,
            placeId = placeId,
            metadata = mapOf(METADATA_CODE to code),
        )
}
