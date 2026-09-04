package uz.mahalla.feature.booking.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import uz.mahalla.data.network.ApiResponse

/**
 * Бронирование по времени (эпик #11, issue #97): услуги заведения, свободные
 * слоты, свои записи.
 *
 * Контракт снят со стенда (`/v3/api-docs` + прямые curl'ы 2026-09-04).
 * `placeId` и `serviceId` — **uuid**: числовой id отвечает
 * `400 TYPE_MISMATCH`. Гео-заголовки обязательны на всех путях (без них
 * `403 GEO_PERMISSION_REQUIRED`), но их ставит `GeoHeaderInterceptor` на обоих
 * клиентах (issue #53).
 *
 * **Услуги и слоты анонимны** (проверено: `200` без токена), а всё, что про
 * саму запись, требует Bearer (`401`). Разделять API по двум Retrofit из-за
 * этого незачем: основной клиент просто добавит заголовок, который читающим
 * ручкам не мешает, — а вот «голый» `@RefreshClient` сломал бы запись.
 * Поэтому API целиком собирается на **основном** Retrofit.
 */
interface BookingApi {

    /** Услуги заведения. `data` — массив `ServiceResponse`. */
    @GET("barber-services/places/{placeId}")
    suspend fun services(@Path("placeId") placeId: String): ApiResponse<List<ServiceDto>>

    /**
     * Свободные слоты на день. `data` — **массив строк**
     * (`ApiResponseListString`), а не объектов: сервер отдаёт готовое время
     * (`"10:00"`/`"10:00:00"`), занятое в него уже не попадает.
     *
     * Оба query-параметра обязательны, `date` — `yyyy-MM-dd`
     * (`400 TYPE_MISMATCH` на любой другой формат), неизвестная услуга даёт
     * `404 NOT_FOUND`.
     */
    @GET("barber-services/places/{placeId}/slots")
    suspend fun slots(
        @Path("placeId") placeId: String,
        @Query("serviceId") serviceId: String,
        @Query("date") date: String,
    ): ApiResponse<List<String>>

    /** Создать запись. Требует Bearer. */
    @POST("appointments")
    suspend fun book(@Body body: BookAppointmentRequest): ApiResponse<AppointmentDto>

    /** Свои записи, страницами. Требует Bearer. */
    @GET("appointments/my")
    suspend fun myAppointments(
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ApiResponse<AppointmentPageDto>

    /**
     * Отмена. Ответ — та же запись, но разбирать его обязательным не считаем:
     * успешный запрос уже означает, что запись отменена (см.
     * [BookingRepository]).
     */
    @POST("appointments/{id}/cancel")
    suspend fun cancel(@Path("id") appointmentId: String): ApiResponse<AppointmentDto>
}

/**
 * Тело `POST /api/v1/appointments` — **самое рискованное место этой задачи**.
 *
 * В `/v3/api-docs` оно объявлено как `BookRequest`, а это имя перекрыто
 * коллизией springdoc: на него ссылаются **три** пути (`appointments`,
 * `gaming/bookings`, `hospitals/appointments`), и показан один набор полей —
 * `{doctorId, date, startTime, complaint}`, то есть заведомо больничный
 * вариант. Групповых документов (`/v3/api-docs/{group}`), где коллизии бы не
 * было, у стенда нет: `swagger-config` отдаёт единственный `url`. Живым
 * запросом форму тела тоже не проверить — `401` приходит **до** валидации
 * (проверено и на пустом теле, и на заполненном).
 *
 * Поэтому имена выведены, а не прочитаны, — как для отзывов (issue #76) и
 * заявки заведения (issue #84):
 *
 * - `serviceId` и `placeId` — из ответа того же эндпоинта
 *   (`AppointmentResponse`); `serviceId` здесь занимает место `doctorId`
 *   больничного варианта.
 * - `date` и `startTime` — из самой `BookRequest`: это ровно те два поля,
 *   которые у всех трёх склеенных запросов общие, поэтому шанс, что они
 *   называются так же и у записи к мастеру, наибольший. Обратите внимание:
 *   в **ответе** день называется `apptDate` — имена запроса и ответа у этого
 *   бэкенда расходятся не впервые.
 *
 * Проверять это надо первым делом под токеном. Не совпадёт — бэкенд ответит
 * `VALIDATION_ERROR`, и текст сервера будет виден прямо на экране (issue #34),
 * а чинится расхождение здесь, в одном месте.
 */
@Serializable
data class BookAppointmentRequest(
    @SerialName("placeId") val placeId: String,
    @SerialName("serviceId") val serviceId: String,
    /** `yyyy-MM-dd`. */
    @SerialName("date") val date: String,
    /** `HH:mm:ss` — как Jackson с `JavaTimeModule` читает `LocalTime`. */
    @SerialName("startTime") val startTime: String,
)

/**
 * `ServiceResponse`. Все поля необязательные: отсутствие любого из них — не
 * повод показать экран ошибки вместо списка услуг.
 *
 * `isActive` принимается и под именем `active`: Jackson сериализует
 * `boolean isActive` то так, то так, в зависимости от геттера, а ошибка здесь
 * спрятала бы все услуги заведения (то же правило, что у `isRead` в issue #81
 * и `isAvailable` в issue #94).
 *
 * `freelancerId` объявлен, но в домен не доезжает: услуга открывается с
 * карточки заведения, и мастера в приложении пока не выбирают (записи к
 * конкретному сотруднику в контракте нет).
 */
@Serializable
data class ServiceDto(
    @SerialName("id") val id: String? = null,
    @SerialName("freelancerId") val freelancerId: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("priceAmount") val priceAmount: Long? = null,
    @SerialName("durationMinutes") val durationMinutes: Int? = null,
    @SerialName("isActive") val isActive: Boolean? = null,
    @SerialName("active") val active: Boolean? = null,
)

/**
 * `AppointmentResponse`. Имя в схеме встречается один раз — коллизии здесь
 * нет, поля прочитаны как есть.
 *
 * [startTime] и [endTime] типизированы как [JsonElement] по той же причине,
 * что `counterTime` талона очереди (issue #96): springdoc описывает
 * `LocalTime` объектом `{hour, minute, second, nano}`, а Jackson с
 * `JavaTimeModule` отдаёт строку `"14:30:00"`. Ошибка в типе уронила бы разбор
 * **всей** записи. Разбирает оба вида `parseServerLocalTime`.
 */
@Serializable
data class AppointmentDto(
    @SerialName("id") val id: String? = null,
    @SerialName("placeId") val placeId: String? = null,
    @SerialName("userId") val userId: String? = null,
    @SerialName("serviceId") val serviceId: String? = null,
    @SerialName("serviceName") val serviceName: String? = null,
    @SerialName("price") val price: Long? = null,
    /** `yyyy-MM-dd`. */
    @SerialName("apptDate") val apptDate: String? = null,
    @SerialName("startTime") val startTime: JsonElement? = null,
    @SerialName("endTime") val endTime: JsonElement? = null,
    @SerialName("status") val status: String? = null,
    /** ISO-8601; Jackson отдаёт и без зоны — разбирает `parseServerInstant`. */
    @SerialName("createdAt") val createdAt: String? = null,
)

/** `PageResponseAppointmentResponse`. */
@Serializable
data class AppointmentPageDto(
    @SerialName("content") val content: List<AppointmentDto> = emptyList(),
    @SerialName("page") val page: Int? = null,
    @SerialName("size") val size: Int? = null,
    @SerialName("totalElements") val totalElements: Long? = null,
    @SerialName("totalPages") val totalPages: Int? = null,
    @SerialName("first") val first: Boolean? = null,
    @SerialName("last") val last: Boolean? = null,
)
