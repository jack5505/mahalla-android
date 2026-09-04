package uz.mahalla.feature.queue.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import uz.mahalla.data.network.ApiResponse

/**
 * Электронная очередь (walk-in) заведения — эпик #10, issue #96.
 *
 * Контракт снят со стенда (`/v3/api-docs` + прямые curl'ы 2026-09-04). В
 * контроллере семь путей, и клиенту принадлежат **два**: `send` и `cancel`.
 * Остальные (`accept`/`decline`/`start`/`complete`/`barber/dashboard`) ведёт
 * заведение из бизнес-панели (эпик #16).
 *
 * Оба вызова требуют Bearer (без токена `401 UNAUTHORIZED`) — значит API
 * собирается на **основном** Retrofit, а не на «голом» `@RefreshClient`.
 * Гео-заголовки тоже обязательны (без них `403 GEO_PERMISSION_REQUIRED`), но
 * их ставит `GeoHeaderInterceptor` на обоих клиентах (issue #53).
 *
 * **Ручки чтения своего талона у бэкенда нет.** Ни `GET walkin/my`, ни
 * `GET walkin/{id}`: состояние приезжает только в ответах на эти два вызова, а
 * `GET orders` про walk-in не знает вовсе (`vertical` там —
 * `FOOD, CLOTHING, PHARMACY, CINEMA, GAMING`). Поэтому опроса статуса на
 * экране талона нет: опрашивать нечего. Задача заведена в `jack5505/mahalla`
 * (см. отчёт в issue #96); когда ручка появится, опрос добавляется в
 * `QueueViewModel` по образцу `OrderStatusViewModel`.
 */
interface WalkInApi {

    /** Записаться в очередь. Обязательны `placeId` и `userName`. */
    @POST("walkin/send")
    suspend fun send(@Body body: SendWalkInRequest): ApiResponse<WalkInDto>

    /**
     * Отмена клиентом. Ответ — тот же талон, но разбирать его обязательным не
     * считаем: успешный запрос уже означает, что талон отменён (см.
     * [WalkInRepository]).
     */
    @POST("walkin/{id}/cancel")
    suspend fun cancel(@Path("id") ticketId: String): ApiResponse<WalkInDto>
}

/**
 * `SendRequest`. Пустая услуга уходит **отсутствующим** полем, а не `null`:
 * `explicitNulls = false` выбрасывает её из тела, и бэкенд получает ровно то,
 * что человек заполнил.
 */
@Serializable
data class SendWalkInRequest(
    @SerialName("placeId") val placeId: String,
    @SerialName("userName") val userName: String,
    @SerialName("serviceName") val serviceName: String? = null,
)

/**
 * `Response` walk-in-контроллера. Все поля необязательные: отсутствие любого
 * из них — не повод показать ошибку вместо талона.
 *
 * **Это имя в `/v3/api-docs` перекрыто коллизией springdoc** (под `Response`
 * склеены walk-in, `place-staff` и `reviews`), но уцелел именно walk-in-вариант
 * — поля здесь читаемы, в отличие от отзывов (issue #76).
 *
 * [counterTime] типизирован как [JsonElement] намеренно: springdoc описывает
 * `LocalTime` объектом `{hour, minute, second, nano}`, а Jackson с
 * `JavaTimeModule` отдаёт строку `"14:30:00"`. Проверить живым запросом
 * нельзя (нужен токен), а ошибка в типе уронила бы разбор **всего** талона —
 * то есть удачную запись превратила бы в «не удалось». Разбирает оба вида
 * [parseCounterTime].
 */
@Serializable
data class WalkInDto(
    @SerialName("id") val id: String? = null,
    @SerialName("placeId") val placeId: String? = null,
    @SerialName("userId") val userId: String? = null,
    @SerialName("staffId") val staffId: String? = null,
    @SerialName("userName") val userName: String? = null,
    @SerialName("serviceName") val serviceName: String? = null,
    @SerialName("barberNote") val barberNote: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("counterTime") val counterTime: JsonElement? = null,
    @SerialName("queuePosition") val queuePosition: Int? = null,
    @SerialName("estimatedWaitMinutes") val estimatedWaitMinutes: Int? = null,
    /** ISO-8601; Jackson отдаёт и без зоны — разбирает `parseServerInstant`. */
    @SerialName("serviceStartedAt") val serviceStartedAt: String? = null,
    @SerialName("createdAt") val createdAt: String? = null,
)
