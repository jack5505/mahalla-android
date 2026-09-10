package uz.mahalla.core.analytics

/**
 * Событие продуктовой аналитики (issue #169).
 *
 * **Аналитика у бэкенда привязана к заведению, а не к экрану.** У ручки
 * `POST analytics/track` `placeId` и `eventType` обязательны, а перечисление
 * видов закрыто девятью значениями ([AnalyticsEventType]) — поэтому «открыли
 * экран настроек», «искали в поиске» и «бэкенд отказал» отправить нечем: у них
 * нет заведения, а нового вида события клиент выдумать не может
 * (`docs/API-CONTRACT.md`, раздел AnalyticsApi; недостающее — issue #226).
 *
 * Отсюда правило: **события создаются только через [AnalyticsEvents]**, а не
 * строками по экранам. Иначе одно и то же действие приедет в панель под двумя
 * именами, и считать воронку будет нечем — ровно то, из-за чего заведена issue.
 */
data class AnalyticsEvent(
    val type: AnalyticsEventType,
    /** Заведение. Пустой — событие не отправляется: сервер ответит `400`. */
    val placeId: String,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Виды события. Список — ровно `TrackEventRequest.eventType` со стенда
 * (`/v3/api-docs`, снят 2026-09-10): свои имена сервер не примет.
 *
 * [serverName] отделён от имени в Kotlin намеренно: enum сериализуется по
 * `serverName`, и переименование варианта в коде не меняет то, что уходит на
 * сервер.
 */
enum class AnalyticsEventType(val serverName: String) {
    /** Карточка заведения открыта. */
    View("VIEW"),

    /** «Нравится» на карточке. Экрана пока нет — соцфункции в issue #75. */
    Like("LIKE"),

    /** «В избранное». Экрана пока нет — issue #75. */
    Save("SAVE"),

    /** «Поделиться». Кнопки пока нет ни в одной вертикали. */
    Share("SHARE"),

    /** Нажали «Позвонить» на карточке. */
    Call("CALL"),

    /** Нажали «Маршрут» на карточке. */
    Navigate("NAVIGATE"),

    /**
     * Запись/бронь/талон/билет созданы — сервер принял запрос. Талон в
     * очереди уходит и со статусом `PENDING`: он создан, а примет ли его
     * мастер — следующий шаг, у которого своего вида события нет.
     */
    Book("BOOK"),

    /** Заказ создан — сервер подтвердил. */
    Order("ORDER"),

    /** Отзыв отправлен — сервер подтвердил. */
    Review("REVIEW"),
}

/**
 * Вертикаль, из которой пришло событие.
 *
 * `BOOK` и `ORDER` у бэкенда по одному на все вертикали: и талон в очередь, и
 * билет в кино, и бронь игровой зоны — это `BOOK`. Различить их в панели можно
 * только по [AnalyticsEvent.metadata], поэтому вертикаль уезжает туда под
 * ключом [AnalyticsEvents.METADATA_VERTICAL].
 *
 * Содержимое `metadata` **не сверено** — схема объявляет его свободным
 * объектом, но что бэкенд с ним делает, из схемы не следует (issue #169).
 */
enum class AnalyticsVertical(val serverName: String) {
    Food("food"),
    Fashion("fashion"),
    Queue("queue"),
    Booking("booking"),
    Hospital("hospital"),
    Gaming("gaming"),
    Cinema("cinema"),
}

/**
 * Фабрика событий: единственное место, где заводится [AnalyticsEvent].
 *
 * Названа по действию человека, а не по виду события бэкенда — экрану не нужно
 * знать, что «взял талон» и «купил билет» уезжают одним `BOOK`.
 */
object AnalyticsEvents {

    /** Ключ вертикали в `metadata`. Один на всё приложение. */
    const val METADATA_VERTICAL = "vertical"

    /** Карточка заведения открылась. */
    fun placeViewed(placeId: String): AnalyticsEvent =
        AnalyticsEvent(type = AnalyticsEventType.View, placeId = placeId)

    /** Нажали «Позвонить». */
    fun placeCalled(placeId: String): AnalyticsEvent =
        AnalyticsEvent(type = AnalyticsEventType.Call, placeId = placeId)

    /** Нажали «Маршрут». */
    fun routeRequested(placeId: String): AnalyticsEvent =
        AnalyticsEvent(type = AnalyticsEventType.Navigate, placeId = placeId)

    /** Отзыв принят сервером. */
    fun reviewSubmitted(placeId: String): AnalyticsEvent =
        AnalyticsEvent(type = AnalyticsEventType.Review, placeId = placeId)

    /** Запись, бронь, талон или билет приняты сервером ([AnalyticsEventType.Book]). */
    fun booked(placeId: String, vertical: AnalyticsVertical): AnalyticsEvent =
        AnalyticsEvent(
            type = AnalyticsEventType.Book,
            placeId = placeId,
            metadata = mapOf(METADATA_VERTICAL to vertical.serverName),
        )

    /** Заказ подтверждён сервером. */
    fun ordered(placeId: String, vertical: AnalyticsVertical): AnalyticsEvent =
        AnalyticsEvent(
            type = AnalyticsEventType.Order,
            placeId = placeId,
            metadata = mapOf(METADATA_VERTICAL to vertical.serverName),
        )
}
