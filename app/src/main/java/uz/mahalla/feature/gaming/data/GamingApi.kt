package uz.mahalla.feature.gaming.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import uz.mahalla.data.network.ApiResponse

/**
 * Игровые зоны и брони (эпик #11, issue #98).
 *
 * Контракт снят со стенда (`/v3/api-docs` + прямые curl'ы 2026-09-04). В
 * `gaming-controller` пять путей, и клиенту принадлежат **три**: зоны
 * заведения, создание брони и свои брони. Оставшиеся два
 * (`POST places/{placeId}/zones`, `PUT places/{placeId}/bookings/{id}/complete`)
 * ведёт заведение из бизнес-панели (эпик #16).
 *
 * **Отмены брони у бэкенда нет вовсе** — ни в этом контроллере, ни в общем
 * `orders` (там для `GAMING` есть только `GET`). Поэтому её нет и в
 * приложении: кнопка, которую нечем выполнить, хуже её отсутствия. Задача
 * заведена в отчёте по issue #98.
 *
 * Гео-заголовки обязательны на всех трёх (без них `403
 * GEO_PERMISSION_REQUIRED`), но их ставит `GeoHeaderInterceptor` (issue #53).
 */
interface GamingApi {

    /**
     * Зоны заведения. **Ручка анонимна** — проверено curl'ом: без токена
     * приходит `200` с `data: []`. Значит список зон виден и до входа, как
     * меню в «Еде» (issue #9), и это правильно: бронировать нельзя, а
     * посмотреть цены можно.
     *
     * `placeId` в схеме — `uuid`.
     */
    @GET("gaming/places/{placeId}/zones")
    suspend fun zones(@Path("placeId") placeId: String): ApiResponse<List<GamingZoneDto>>

    /**
     * Забронировать зону. Требует Bearer (`401 UNAUTHORIZED` без токена).
     *
     * **Форма тела не подтверждена контрактом.** В схеме тело объявлено как
     * `BookRequest`, а это имя перекрыто коллизией springdoc: на него
     * ссылаются три пути (`/hospitals/appointments`, `/appointments` и этот),
     * и уцелел медицинский вариант (`{doctorId, date, startTime, complaint}`)
     * — тело записи к врачу, а не брони зоны. Живым запросом форму тоже не
     * снять: `401` приходит **до** валидации (проверено и на пустом теле, и
     * на заполненном).
     *
     * Поэтому поля названы так же, как в ответе того же эндпоинта
     * (`GamingBooking`: `zoneId`, `startTime`, `durationHours`) — то же
     * решение, что принято для отзывов (issue #76) и заявки продавца
     * (issue #84). **Это первое, что надо проверить руками под токеном.**
     */
    @POST("gaming/bookings")
    suspend fun book(@Body body: CreateGamingBookingRequest): ApiResponse<GamingBookingDto>

    /**
     * Свои брони страницами (`ApiResponsePageResponseGamingBooking`). Схемы
     * `PageResponseGamingBooking` и `GamingBooking` в `/v3/api-docs`
     * встречаются по одному разу — коллизии здесь нет, поля взяты как есть.
     */
    @GET("gaming/bookings/my")
    suspend fun myBookings(
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ApiResponse<GamingBookingPageDto>
}

/**
 * Тело брони (см. предупреждение о коллизии в [GamingApi.book]).
 *
 * [startTime] уходит **местным** временем без зоны (`2026-09-05T18:30:00`):
 * так его отдаёт сам бэкенд в ответах (Jackson сериализует `LocalDateTime`
 * без зоны — правило `parseServerInstant`), и так его примет `LocalDateTime`
 * на той стороне. Строка со смещением на поле `LocalDateTime` разобралась бы
 * не везде, а зона в Узбекистане одна.
 */
@Serializable
data class CreateGamingBookingRequest(
    @SerialName("zoneId") val zoneId: String,
    @SerialName("startTime") val startTime: String,
    @SerialName("durationHours") val durationHours: Int,
)

/**
 * `GamingZone`. Все поля необязательные: отсутствие любого из них — не повод
 * показать ошибку вместо списка зон.
 */
@Serializable
data class GamingZoneDto(
    @SerialName("id") val id: String? = null,
    @SerialName("placeId") val placeId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("zoneType") val zoneType: String? = null,
    @SerialName("pricePerHour") val pricePerHour: Long? = null,
    @SerialName("totalSeats") val totalSeats: Int? = null,
    /**
     * Jackson сериализует `boolean isAvailable` то как `isAvailable`, то как
     * `available` — принимаем оба имени. Ошибка здесь увела бы в «закрыто»
     * все зоны сразу (то же правило, что у `isRead` в issue #81).
     */
    @SerialName("isAvailable") val isAvailable: Boolean? = null,
    @SerialName("available") val available: Boolean? = null,
)

/** `GamingBooking`. */
@Serializable
data class GamingBookingDto(
    @SerialName("id") val id: String? = null,
    @SerialName("zoneId") val zoneId: String? = null,
    @SerialName("placeId") val placeId: String? = null,
    @SerialName("userId") val userId: String? = null,
    /** ISO-8601; Jackson отдаёт и без зоны — разбирает `parseServerInstant`. */
    @SerialName("startTime") val startTime: String? = null,
    @SerialName("endTime") val endTime: String? = null,
    @SerialName("durationHours") val durationHours: Int? = null,
    @SerialName("totalPrice") val totalPrice: Long? = null,
    @SerialName("status") val status: String? = null,
)

/** `PageResponseGamingBooking`. */
@Serializable
data class GamingBookingPageDto(
    @SerialName("content") val content: List<GamingBookingDto> = emptyList(),
    @SerialName("page") val page: Int? = null,
    @SerialName("size") val size: Int? = null,
    @SerialName("totalElements") val totalElements: Long? = null,
    @SerialName("totalPages") val totalPages: Int? = null,
    @SerialName("first") val first: Boolean? = null,
    @SerialName("last") val last: Boolean? = null,
)
