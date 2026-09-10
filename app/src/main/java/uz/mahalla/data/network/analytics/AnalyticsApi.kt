package uz.mahalla.data.network.analytics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.POST
import uz.mahalla.data.network.ApiResponse

/**
 * Продуктовая аналитика (контроллер `analytics`, issue #169).
 *
 * Контракт снят со стенда 2026-09-10 (`/v3/api-docs` + curl'ы):
 *
 * | ручка | без токена | тело |
 * |---|---|---|
 * | `POST analytics/track` | **`401 UNAUTHORIZED`** | `TrackEventRequest` |
 *
 * Три вещи, из которых следует всё остальное устройство отправки:
 *
 * 1. **Токен обязателен.** Аноним с гео-заголовками получает `401`, поэтому
 *    события до входа отправлять некуда — `DefaultAnalyticsRepository` их не
 *    шлёт вовсе, а не тратит запрос на заведомый отказ.
 * 2. **Батчинга нет.** Под `analytics` у бэкенда ровно два пути — этот `track`
 *    и `places/{placeId}/dashboard` (бизнес-панель, эпик #16). Ручки вроде
 *    `analytics/track/batch` в схеме нет, значит одно событие — один запрос.
 * 3. **Поля времени события в запросе нет.** Момент события — это момент, когда
 *    запрос доехал до сервера, и отложенная отправка молча сдвинула бы всю
 *    воронку. Отсюда решение не копить события в офлайне (`docs/adr/0006`).
 *
 * `lat`/`lng` объявлены в схеме бэкенда, а в [TrackEventRequest] не заведены
 * вовсе: координаты уже уходят в
 * `X-Geo-Lat`/`X-Geo-Lng` (`GeoHeaderInterceptor`). **Это допущение, а не
 * проверенный факт:** правило «координаты не дублировать» в контракте написано
 * про query-параметры, а берёт ли бэкенд гео события из заголовков — из схемы
 * не следует. Если не берёт, все события приедут без координат, и гео-половина
 * `places/{placeId}/dashboard` останется пустой — молча (issue #226).
 * API собирается на **основном** Retrofit — ради
 * `AuthInterceptor` и `TokenAuthenticator`: событие после протухшего access
 * должно уехать после refresh, а не потеряться.
 */
interface AnalyticsApi {

    /** Ответ — конверт без полезной нагрузки: `data` пуст и при успехе. */
    @POST("analytics/track")
    suspend fun track(@Body body: TrackEventRequest): ApiResponse<JsonElement>
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
