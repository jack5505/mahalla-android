package uz.mahalla.data.network.analytics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.POST
import uz.mahalla.data.network.ApiResponse

/**
 * Продуктовая аналитика (контроллер `analytics`, issue #169, #226).
 *
 * Два пути с разным устройством отправки — не потому, что так красивее, а
 * потому, что бэкенд их сам развёл по разным правилам:
 *
 * | ручка | заведение | без identity | батч | время события |
 * |---|---|---|---|---|
 * | `POST analytics/track` | обязательно | `401` | нет | нет (issue #226) |
 * | `POST analytics/events` | необязательно | `400 ANALYTICS_IDENTITY_REQUIRED` без `deviceId` **и** токена | до 100 | `occurredAt`, клиент задаёт сам |
 *
 * ## `track`
 *
 * Контракт снят со стенда 2026-09-10: `TrackEventRequest`, `eventType` —
 * закрытое перечисление из девяти значений. Токен обязателен (аноним с
 * гео-заголовками получает `401`), поэтому события до входа
 * `DefaultAnalyticsRepository` не шлёт вовсе. `lat`/`lng` объявлены в схеме, а
 * в [TrackEventRequest] не заведены: координаты уже уходят в
 * `X-Geo-Lat`/`X-Geo-Lng` (`GeoHeaderInterceptor`) — берёт ли их бэкенд из
 * заголовков для этой ручки, из схемы не следует (допущение, issue #226).
 *
 * ## `events`
 *
 * Контракт зафиксирован issue #226 (комментарий бэкенда 2026-09-19,
 * `jack5505/mahalla#217`): свободное `name` вместо закрытого перечисления,
 * `placeId` необязателен, `deviceId` — ключ identity без токена (**слать и
 * под токеном тоже**: так анонимная часть воронки сшивается с авторизованной),
 * `occurredAt` — ISO-8601 UTC, не старше 30 суток и не более чем на 5 минут в
 * будущем. Ответ — `accepted`: сколько из пачки реально записано; может быть
 * меньше присланного (события вне окна `occurredAt` или с `metadata` больше
 * 4 КБ отбрасываются поштучно, остальные из пачки — нет), **переотправлять
 * разницу не нужно**, повтор её не спасёт. Целиком запрос отклоняется только
 * на `VALIDATION_ERROR` (400), `ANALYTICS_IDENTITY_REQUIRED` (400),
 * `RATE_LIMITED` (429) и 5xx — состав ручек-констант классификации см.
 * `DefaultAnalyticsEventQueue`. Дедупликации на сервере нет (`jack5505/mahalla#267`
 * открыт под неё) — повтор пачки после таймаута задвоит события, поэтому
 * ретраится только то, что осталось в очереди, а не последний ответ.
 *
 * Обе ручки — на **основном** Retrofit (issue #228, п. 1: осознанный
 * компромисс, `TokenAuthenticator` может разлогинить по фоновому событию, но
 * мёртвая сессия мертва независимо от того, кто её обнаружил первым). Для
 * `events` это не критично: ручка работает и без токена, поэтому запрос не
 * шлётся заведомым отказом даже без сессии, а `deviceId` не даёт очереди
 * зависнуть на одном протухшем `Authorization`.
 */
interface AnalyticsApi {

    /** Ответ — конверт без полезной нагрузки: `data` пуст и при успехе. */
    @POST("analytics/track")
    suspend fun track(@Body body: TrackEventRequest): ApiResponse<JsonElement>

    /** `data.accepted` — см. KDoc интерфейса. */
    @POST("analytics/events")
    suspend fun events(@Body body: AnalyticsEventsRequest): ApiResponse<AnalyticsEventsResponse>
}

/**
 * `TrackEventRequest`. Обязательны `placeId` и `eventType`; `eventType` —
 * закрытое перечисление, чужое значение сервер не примет.
 *
 * `metadata` — свободный объект по схеме; клиент кладёт туда только вертикаль
 * (`AnalyticsEvents.METADATA_VERTICAL`). Пустая карта не отправляется:
 * `metadata: {}` ничего не сообщает, а отличить «не прислали» от «прислали
 * пустое» в панели нечем.
 */
@Serializable
data class TrackEventRequest(
    @SerialName("placeId") val placeId: String,
    @SerialName("eventType") val eventType: String,
    @SerialName("metadata") val metadata: Map<String, String>? = null,
)

/** Тело `POST analytics/events`. `deviceId` — всегда, независимо от токена. */
@Serializable
data class AnalyticsEventsRequest(
    @SerialName("deviceId") val deviceId: String,
    @SerialName("events") val events: List<AnalyticsEventItemRequest>,
)

/**
 * Одно событие пачки. `name` — свободная строка (маска `[A-Za-z0-9_.:-]`,
 * ≤ 64 символа — обе стороны следит клиент, обе завёл сервер), `occurredAt` —
 * ISO-8601 UTC момента, когда событие произошло у клиента, а не когда дошло
 * до сервера (issue #226 — этим и отличается от `TrackEventRequest`).
 */
@Serializable
data class AnalyticsEventItemRequest(
    @SerialName("name") val name: String,
    @SerialName("occurredAt") val occurredAt: String,
    @SerialName("placeId") val placeId: String? = null,
    @SerialName("metadata") val metadata: Map<String, String>? = null,
)

/** `accepted` — сколько событий пачки реально записано, см. KDoc интерфейса. */
@Serializable
data class AnalyticsEventsResponse(
    @SerialName("accepted") val accepted: Int = 0,
)
