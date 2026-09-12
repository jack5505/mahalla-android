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

    /** Услуги заведения. `data` — массив `AppointmentServiceResponse`. */
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
 * Тело `POST /api/v1/appointments` — **сверено чтением схемы** (2026-09-10,
 * issue #154).
 *
 * Долго было самым рискованным местом вертикали: в `/v3/api-docs` тело
 * называлось `BookRequest`, а на это имя ссылались **три** пути
 * (`appointments`, `gaming/bookings`, `hospitals/appointments`), и побеждал
 * больничный набор `{doctorId, date, startTime, complaint}`. Живым запросом
 * форму тоже не проверить — `401` приходит **до** валидации. Поэтому имена
 * здесь были выведены, а не прочитаны.
 *
 * Теперь коллизия разведена, схема читается как есть:
 * `AppointmentBookRequest {placeId, serviceId, serviceName, date, startTime}`,
 * обязательные — `date`, `placeId`, `startTime`. **Выведенные имена оказались
 * верны, менять нечего.**
 *
 * `serviceName` не отправляется намеренно: поле необязательное, а имя услуги у
 * сервера уже есть по `serviceId` — дублировать его с клиента значит дать двум
 * источникам разойтись.
 *
 * Обратите внимание: в **ответе** день называется `apptDate` — имена запроса и
 * ответа у этого бэкенда расходятся не впервые.
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
 * `AppointmentServiceResponse`. Все поля необязательные: отсутствие любого из
 * них — не повод показать экран ошибки вместо списка услуг.
 *
 * До issue #216 этим же DTO по ошибке разбирался и ответ мастеров
 * (`FreelancerApi`, `GET freelancers/{id}/services`), а там схема другая —
 * `FreelancerServiceResponse` с `title` и `priceAmount`. Пока эти две ручки
 * выглядели одной схемой `ServiceResponse`, это было незаметно; после развода
 * коллизии стало видно, что у услуг мастера было пустое название и цена 0.
 * Теперь у мастеров свой `FreelancerServiceDto`, этот тип — только для
 * барбершопа.
 *
 * **Имена сверены с живым стендом** контрактной пробой (`contract/booking.sh`,
 * фикстура `services.json`). До неё здесь стояли выведенные из схемы `title` и
 * `priceAmount` — стенд шлёт `name` и `price`, поэтому у каждой услуги на
 * экране пропадали и название, и цена.
 *
 * `colorHex` стенд шлёт, но в домен он не идёт: цвет услуги экрану не нужен.
 * Объявлен, чтобы контрактный тест видел поле как известное, а не как утечку.
 *
 * `description` и `freelancerId` **схемой не предусмотрены** (сверено
 * 2026-09-10): оба поля принадлежат `FreelancerServiceResponse`, то есть
 * вертикали мастеров, и здесь были видны только из-за склейки имён. Описания
 * услуги на экране записи не будет, пока бэкенд его не добавит, а выбирать
 * мастера в барбершопе нечем — issue #154, пункт 2.
 *
 * `isActive` принимается и под именем `active`: Jackson сериализует
 * `boolean isActive` то так, то так, в зависимости от геттера, а ошибка здесь
 * спрятала бы все услуги заведения (то же правило, что у `isRead` в issue #81
 * и `isAvailable` в issue #94). В схеме флага нет ни под одним из имён, и в
 * пробе он не встретился — поэтому отсутствие пары контрактный тест считает
 * допустимым, но сама пара оставлена: разбор мягкий, лишнее известное поле
 * дешевле пропавшего списка.
 */
@Serializable
data class ServiceDto(
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("colorHex") val colorHex: String? = null,
    /** Тийины; в сумы переводит маппер — `Money.tiyinToSom` (issue #149). */
    @SerialName("price") val price: Long? = null,
    @SerialName("durationMinutes") val durationMinutes: Int? = null,
    @SerialName("isActive") val isActive: Boolean? = null,
    @SerialName("active") val active: Boolean? = null,
)

/**
 * `AppointmentBookingResponse` — поля прочитаны как есть (сверено 2026-09-10).
 * `status` у бэкенда enum: `PENDING | CONFIRMED | CANCELLED | COMPLETED |
 * NO_SHOW`; здесь он строка намеренно — новое значение не должно ронять разбор
 * всей записи.
 *
 * Ни `prepayment`, ни `paid`, ни суммы к оплате в ответе нет: предоплата брони
 * у бэкенда не предусмотрена (issue #154, пункт 3).
 *
 * [price] — **тийины** (бэкенд подтвердил единицу, см. `docs/API-CONTRACT.md`);
 * в `priceSum` сумами его переводит `BookingMappers` через `Money.tiyinToSom`
 * (issue #149, PR #233).
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
    /** Тийины; в сумы переводит маппер — `Money.tiyinToSom` (issue #149). */
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
