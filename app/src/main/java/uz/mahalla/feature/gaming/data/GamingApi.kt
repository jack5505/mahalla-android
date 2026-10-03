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
 * Игровые зоны и брони (эпик #11, issue #98, #406).
 *
 * Контракт снят со стенда (`/v3/api-docs` + прямые curl'ы 2026-09-04,
 * зоны и места — повторно curl'ом 2026-10-02 после выката бэкенда
 * jack5505/mahalla#363). В `gaming-controller` ручки, и клиенту принадлежат
 * **четыре**: зоны заведения, места зоны, создание брони и свои брони.
 * Оставшиеся два (`POST places/{placeId}/zones`,
 * `PUT places/{placeId}/bookings/{id}/complete`) ведёт заведение из
 * бизнес-панели (эпик #16).
 *
 * **Отмены брони у бэкенда нет вовсе** — ни в этом контроллере, ни в общем
 * `orders` (там для `GAMING` есть только `GET`). Поэтому её нет и в
 * приложении: кнопка, которую нечем выполнить, хуже её отсутствия. Задача
 * заведена в отчёте по issue #98.
 *
 * Гео-заголовки обязательны на всех путях (без них `403
 * GEO_PERMISSION_REQUIRED`), но их ставит `GeoHeaderInterceptor` (issue #53).
 */
interface GamingApi {

    /**
     * Зоны заведения. **Ручка анонимна** — проверено curl'ом: без токена
     * приходит `200` с `data: []`. Значит список зон виден и до входа, как
     * меню в «Еде» (issue #9), и это правильно: бронировать нельзя, а
     * посмотреть цены можно.
     *
     * `placeId` в схеме — `uuid`. После jack5505/mahalla#363 (issue #406)
     * `totalSeats` ушёл, вместо него `totalUnits` — число реальных мест зоны.
     */
    @GET("gaming/places/{placeId}/zones")
    suspend fun zones(@Path("placeId") placeId: String): ApiResponse<List<GamingZoneDto>>

    /**
     * Места зоны: нумерованные позиции, по которым бэкенд считает
     * [GamingZoneDto.totalUnits] (issue #406). **Ручка анонимна** — тот же
     * curl 2026-10-02 без токена дал `200` с местами зоны, как и у списка зон.
     *
     * Выбора места в этом шаге ещё нет: бронь по-прежнему уходит `zoneId`
     * ([book]), а список здесь — только справочник, что именно в зоне (issue
     * #406). Бронь по `unitId` — следующий бэкенд-шаг (V42), отдельная задача.
     */
    @GET("gaming/places/{placeId}/zones/{zoneId}/units")
    suspend fun units(
        @Path("placeId") placeId: String,
        @Path("zoneId") zoneId: String,
    ): ApiResponse<List<GamingUnitDto>>

    /**
     * Забронировать зону. Требует Bearer (`401 UNAUTHORIZED` без токена).
     *
     * **Форма тела сверена чтением схемы** (2026-09-10, issue #154):
     * `GamingBookRequest {zoneId, startTime, durationHours}`.
     *
     * Раньше тело называлось `BookRequest`, а это имя было перекрыто коллизией
     * springdoc: на него ссылались три пути (`/hospitals/appointments`,
     * `/appointments` и этот), и уцелел медицинский вариант
     * (`{doctorId, date, startTime, complaint}`) — тело записи к врачу, а не
     * брони зоны. Поэтому поля здесь были названы по ответу того же эндпоинта
     * (`GamingBooking`) — то же решение, что для отзывов (issue #76) и заявки
     * продавца (issue #84). Коллизия разведена, **выведенные имена оказались
     * верны, менять нечего.**
     *
     * Сам ответ под токеном по-прежнему не снят: `401` приходит **до**
     * валидации, а `CONTRACT_REFRESH_TOKEN` в CI нет.
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
 * [startTime] уходит **местным ташкентским** временем без зоны
 * (`2026-09-05T18:30:00` = 18:30 по часам заведения): так его отдаёт сам
 * бэкенд в ответах (Jackson сериализует `LocalDateTime` без зоны), и так его
 * примет `LocalDateTime` на той стороне. Строка со смещением на поле
 * `LocalDateTime` разобралась бы не везде, а зона в Узбекистане одна.
 *
 * Зону выбирает `gamingRequestTime`, читает обратно
 * `parseServerSlotInstant` — одна трактовка на отправку и на чтение
 * (issue #144), менять её можно только в обеих сразу.
 */
@Serializable
data class CreateGamingBookingRequest(
    @SerialName("zoneId") val zoneId: String,
    @SerialName("startTime") val startTime: String,
    @SerialName("durationHours") val durationHours: Int,
)

/**
 * `GamingZoneResponse`. Все поля необязательные: отсутствие любого из них —
 * не повод показать ошибку вместо списка зон.
 *
 * С jack5505/mahalla#363 (issue #406) `totalSeats` заменён на `totalUnits`
 * (число реальных мест, не оценка вместимости), а `zoneType` — закрытый
 * справочник; разбор неизвестного значения в `OTHER` — дело маппера
 * ([uz.mahalla.feature.gaming.domain.GamingZoneType.fromApi]), не
 * сериализатора: здесь поле остаётся сырой строкой, иначе `kotlinx.serialization`
 * уронит всю зону на будущем значении справочника.
 */
@Serializable
data class GamingZoneDto(
    @SerialName("id") val id: String? = null,
    @SerialName("placeId") val placeId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("zoneType") val zoneType: String? = null,
    @SerialName("pricePerHour") val pricePerHour: Long? = null,
    @SerialName("totalUnits") val totalUnits: Long? = null,
    /**
     * Jackson сериализует `boolean isAvailable` то как `isAvailable`, то как
     * `available` — принимаем оба имени. Ошибка здесь увела бы в «закрыто»
     * все зоны сразу (то же правило, что у `isRead` в issue #81).
     */
    @SerialName("isAvailable") val isAvailable: Boolean? = null,
    @SerialName("available") val available: Boolean? = null,
)

/**
 * `GamingUnitResponse` (issue #406) — одно нумерованное место зоны.
 *
 * `seats > 1` — кабина на несколько посадочных мест, бронируется целиком;
 * отдельных мест внутри кабины у бэкенда нет. На стенде 2026-10-02 все места
 * пришли с `seats: 1` — тестовые данные кабину не завели, но поле в схеме
 * есть и задача прямо просит его разбирать.
 */
@Serializable
data class GamingUnitDto(
    @SerialName("id") val id: String? = null,
    @SerialName("zoneId") val zoneId: String? = null,
    @SerialName("number") val number: Int? = null,
    @SerialName("seats") val seats: Int? = null,
)

/** `GamingBooking`. */
@Serializable
data class GamingBookingDto(
    @SerialName("id") val id: String? = null,
    @SerialName("zoneId") val zoneId: String? = null,
    @SerialName("placeId") val placeId: String? = null,
    @SerialName("userId") val userId: String? = null,
    /**
     * ISO-8601; Jackson отдаёт и без зоны. Это **время слота**, а не отметка
     * сервера, поэтому зоне-менее строка читается как местное ташкентское —
     * `parseServerSlotInstant`, а не `parseServerInstant` (issue #144).
     */
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
