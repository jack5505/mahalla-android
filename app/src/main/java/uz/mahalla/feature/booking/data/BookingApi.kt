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
 * Тело `POST /api/v1/appointments` — схема `AppointmentBookRequest`.
 *
 * Имена полей здесь были **выведены**, а не прочитаны: в схеме 2026-09-04 тело
 * называлось `BookRequest`, и это имя перекрывала коллизия springdoc — на него
 * ссылались три пути (`appointments`, `gaming/bookings`,
 * `hospitals/appointments`), а показан был больничный набор
 * `{doctorId, date, startTime, complaint}`.
 *
 * В схеме 2026-09-09 коллизии нет, и собственная `AppointmentBookRequest`
 * **подтверждает догадку** (сверено 2026-09-10, issue #167):
 * `{placeId, serviceId, serviceName, date, startTime}`, обязательны `placeId`,
 * `date`, `startTime`. Лишнее здесь только необязательное `serviceName` —
 * клиент его не шлёт, услугу задаёт `serviceId`. Обратите внимание: в
 * **ответе** день называется `apptDate` — имена запроса и ответа у этого
 * бэкенда расходятся не впервые.
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
 * **Имена сверены с живым стендом** контрактной пробой (`contract/booking.sh`,
 * фикстура `services.json`). До неё здесь стояли выведенные из схемы `title` и
 * `priceAmount` — стенд шлёт `name` и `price`, поэтому у каждой услуги на
 * экране пропадали и название, и цена.
 *
 * `colorHex` стенд шлёт, но в домен он не идёт: цвет услуги экрану не нужен.
 * Объявлен, чтобы контрактный тест видел поле как известное, а не как утечку.
 *
 * `description` стенд не шлёт вовсе — на экране описания услуги не будет,
 * пока бэкенд его не добавит.
 *
 * `isActive` принимается и под именем `active`: Jackson сериализует
 * `boolean isActive` то так, то так, в зависимости от геттера, а ошибка здесь
 * спрятала бы все услуги заведения (то же правило, что у `isRead` в issue #81
 * и `isAvailable` в issue #94). В пробе не встретилось ни одного, ни другого —
 * поэтому отсутствие пары контрактный тест считает допустимым.
 */
@Serializable
data class ServiceDto(
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("colorHex") val colorHex: String? = null,
    @SerialName("price") val price: Long? = null,
    @SerialName("durationMinutes") val durationMinutes: Int? = null,
    @SerialName("isActive") val isActive: Boolean? = null,
    @SerialName("active") val active: Boolean? = null,
)

/**
 * `AppointmentBookingResponse` (в схеме 2026-09-04 — `AppointmentResponse`):
 * поля прочитаны как есть, коллизии имён у ответа не было ни разу.
 *
 * Этими же DTO разбираются ответы больниц, хотя схема у них своя,
 * `HospitalAppointmentResponse` (issue #167): общих полей хватает на всё, что
 * показывает экран, а `doctorId` и `complaint` больничной записи здесь не
 * объявлены и теряются — из-за чего запись к врачу остаётся без имени врача
 * (issue #219).
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

/**
 * Страница записей. Схема у каждой вертикали своя —
 * `PageResponseAppointmentBookingResponse` у брони,
 * `PageResponseHospitalAppointmentResponse` у больниц (issue #167), — но
 * обёртка страницы у них одна и та же, а содержимое разбирается
 * [AppointmentDto].
 */
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
