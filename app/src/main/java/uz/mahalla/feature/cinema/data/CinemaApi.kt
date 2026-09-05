package uz.mahalla.feature.cinema.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import uz.mahalla.data.network.ApiResponse

/**
 * Вертикаль «Кино» (эпик #13, issue #106): афиша, расписание, билеты.
 *
 * Контракт снят со стенда (`/v3/api-docs` + прямые curl'ы 2026-09-04).
 * Гео-заголовки обязательны на всех путях, включая анонимную афишу (без них
 * `403 GEO_PERMISSION_REQUIRED` — проверено), но их ставит
 * `GeoHeaderInterceptor` на обоих клиентах (issue #53).
 *
 * **Что анонимно, а что нет** (проверено запросами):
 *
 * | путь | без токена |
 * |---|---|
 * | `GET cinema/movies` | `200`, `data: []` на пустом каталоге |
 * | `GET cinema/places/{placeId}/schedule?date=…` | `200` |
 * | `GET cinema/movies/{id}` | **`401`** |
 * | `buy`, `tickets/my`, `tickets/{id}/cancel` | `401` |
 *
 * Покупка и билеты требуют Bearer, поэтому API целиком собирается на
 * **основном** Retrofit: «голый» `@RefreshClient` их сломал бы, а читающим
 * ручкам лишний заголовок не мешает.
 */
interface CinemaApi {

    /**
     * Афиша. **Параметров нет ни одного** — ни `placeId`, ни страниц: ручка
     * общая на всю платформу, и афишу конкретного кинотеатра приложение
     * собирает само (`CinemaPoster.forPlace`).
     */
    @GET("cinema/movies")
    suspend fun movies(): ApiResponse<List<MovieDto>>

    /**
     * Расписание кинотеатра на **один** день: `date` обязателен, без него
     * приходит `400 MISSING_PARAMETER` (проверено). `placeId` — uuid:
     * числовой отвечает `400 TYPE_MISMATCH`.
     */
    @GET("cinema/places/{placeId}/schedule")
    suspend fun schedule(
        @Path("placeId") placeId: String,
        /** `yyyy-MM-dd`. */
        @Query("date") date: String,
    ): ApiResponse<List<CinemaSessionDto>>

    /** Купить билет на сеанс. Требует Bearer. */
    @POST("cinema/sessions/{sessionId}/buy")
    suspend fun buy(
        @Path("sessionId") sessionId: String,
        @Body body: BuyTicketRequest,
    ): ApiResponse<CinemaTicketDto>

    /** Свои билеты, страницами. Требует Bearer. */
    @GET("cinema/tickets/my")
    suspend fun myTickets(
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ApiResponse<CinemaTicketPageDto>

    /** Вернуть билет. `PUT`, тела нет. Требует Bearer. */
    @PUT("cinema/tickets/{id}/cancel")
    suspend fun cancel(@Path("id") ticketId: String): ApiResponse<CinemaTicketDto>
}

/**
 * Тело `POST /api/v1/cinema/sessions/{sessionId}/buy`.
 *
 * **Схема тела не описана**: в `/v3/api-docs` там `{"type":"object",
 * "additionalProperties":{"type":"string"}}`, то есть ни одного имени поля.
 * Живым запросом форму не проверить — `401` приходит **до** валидации
 * (пробовал пустое тело, тело с местом и мусорный Bearer: ответ один и тот
 * же).
 *
 * Поэтому имя выведено из ответа того же эндпоинта, как в issue #76, #84 и
 * #97: единственное поле `CinemaTicket`, которое задаёт покупатель, —
 * `seatNumber`. Количество билетов полем быть не может: ответ описан как
 * **один** `CinemaTicket`, а не список, — то есть за запрос покупается один
 * билет.
 *
 * Место необязательно и уходит **отсутствующим** полем (в `Json` проекта
 * `explicitNulls = false`), то есть без него тело — пустой объект `{}`. Это
 * согласуется с `additionalProperties`: сервер читает карту строк, и лишних
 * ключей приложение не шлёт.
 *
 * Тест `CinemaRepositoryTest` закрепляет отправляемое тело — правка после
 * проверки под токеном будет видна одной строкой.
 */
@Serializable
data class BuyTicketRequest(
    @SerialName("seatNumber") val seatNumber: String? = null,
)

/**
 * `Movie`. Имя в схеме встречается один раз — коллизии springdoc нет, поля
 * прочитаны как есть.
 *
 * Все поля необязательные: отсутствие любого из них — не повод показать экран
 * ошибки вместо афиши.
 *
 * `isActive` принимается и под именем `active`: Jackson сериализует
 * `Boolean isActive` то так, то так, в зависимости от геттера, — а ошибка
 * здесь спрятала бы из афиши весь прокат (то же правило, что у `isRead` в
 * issue #81 и `isAvailable` в issue #94).
 */
@Serializable
data class MovieDto(
    @SerialName("id") val id: String? = null,
    @SerialName("placeId") val placeId: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("titleUz") val titleUz: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("genre") val genre: String? = null,
    @SerialName("durationMinutes") val durationMinutes: Int? = null,
    /** `yyyy-MM-dd`. */
    @SerialName("releaseDate") val releaseDate: String? = null,
    @SerialName("posterUrl") val posterUrl: String? = null,
    @SerialName("trailerUrl") val trailerUrl: String? = null,
    /** Возрастное ограничение строкой (`16+`), а не оценка зрителей. */
    @SerialName("rating") val rating: String? = null,
    @SerialName("isActive") val isActive: Boolean? = null,
    @SerialName("active") val active: Boolean? = null,
)

/**
 * `CinemaSession`.
 *
 * [startTime] и [endTime] типизированы `JsonElement`, потому что вид значения
 * из схемы не следует: springdoc описывает `LocalTime` объектом
 * `{hour, minute, second, nano}`, а Jackson с `JavaTimeModule` отдаёт строку
 * `"14:30:00"`. Разбирает оба `parseServerLocalTime`; ошибка в типе уронила
 * бы разбор **всего** расписания (та же грабля, что у `counterTime` очереди в
 * issue #96 и у времени записи в issue #97).
 */
@Serializable
data class CinemaSessionDto(
    @SerialName("id") val id: String? = null,
    @SerialName("placeId") val placeId: String? = null,
    @SerialName("movieId") val movieId: String? = null,
    @SerialName("hallName") val hallName: String? = null,
    /** `yyyy-MM-dd`. */
    @SerialName("sessionDate") val sessionDate: String? = null,
    @SerialName("startTime") val startTime: JsonElement? = null,
    @SerialName("endTime") val endTime: JsonElement? = null,
    @SerialName("ticketPrice") val ticketPrice: Long? = null,
    @SerialName("totalSeats") val totalSeats: Int? = null,
    @SerialName("availableSeats") val availableSeats: Int? = null,
    @SerialName("isActive") val isActive: Boolean? = null,
    @SerialName("active") val active: Boolean? = null,
)

/** `CinemaTicket`. */
@Serializable
data class CinemaTicketDto(
    @SerialName("id") val id: String? = null,
    @SerialName("sessionId") val sessionId: String? = null,
    @SerialName("userId") val userId: String? = null,
    @SerialName("seatNumber") val seatNumber: String? = null,
    @SerialName("price") val price: Long? = null,
    @SerialName("qrCode") val qrCode: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("createdAt") val createdAt: String? = null,
)

/** `PageResponseCinemaTicket`. */
@Serializable
data class CinemaTicketPageDto(
    @SerialName("content") val content: List<CinemaTicketDto> = emptyList(),
    @SerialName("page") val page: Int? = null,
    @SerialName("size") val size: Int? = null,
    @SerialName("totalElements") val totalElements: Long? = null,
    @SerialName("totalPages") val totalPages: Int? = null,
    @SerialName("first") val first: Boolean? = null,
    @SerialName("last") val last: Boolean? = null,
)
