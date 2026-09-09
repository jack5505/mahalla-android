package uz.mahalla.feature.freelancer.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import uz.mahalla.data.network.ApiResponse

/**
 * Вертикаль «Мастера»: каталог фрилансеров, их услуги, заказы (issue #107) и
 * кабинет самого мастера — анкета и выставление услуг (issue #71).
 *
 * Контракт снят со стенда (`/v3/api-docs` + прямые curl'ы 2026-09-04,
 * перепроверен 2026-09-09). Проверено живыми запросами:
 *
 * | запрос | ответ |
 * |---|---|
 * | `GET freelancers` с гео-заголовками | `200`, сегодня двое мастеров |
 * | `GET freelancers` без гео-заголовков | `403 GEO_PERMISSION_REQUIRED` |
 * | `GET freelancers/{uuid}` | `404 NOT_FOUND` «Profil topilmadi» |
 * | `GET freelancers/1` | `400 TYPE_MISMATCH` — `id` это **uuid** |
 * | `GET freelancers/{id}/services` | `200`, поля `title`/`priceAmount` |
 * | вся ветка `freelancers/me`, `POST .../orders` | `401` до валидации тела |
 *
 * Гео-заголовки ставит `GeoHeaderInterceptor` на обоих клиентах (issue #53),
 * так что вопрос закрыт сам собой.
 *
 * **Каталог, профиль и услуги анонимны**, всё остальное требует Bearer.
 * Разделять API по двум Retrofit из-за этого незачем: основной клиент просто
 * добавит заголовок, который читающим ручкам не мешает, — а «голый»
 * `@RefreshClient` сломал бы заказ. Поэтому API целиком собирается на
 * **основном** Retrofit.
 *
 * Чего здесь по-прежнему нет: входящие заказы мастера
 * (`GET freelancers/me/orders`) и смена их статуса
 * (`PUT freelancers/orders/{orderId}/status`) — это бизнес-панель, эпик #16.
 * Выставить услугу без них можно, а принимать заказы — уже другая история.
 */
interface FreelancerApi {

    /**
     * Каталог мастеров, страницами.
     *
     * `profession` и `city` необязательны. Приложение шлёт только первый:
     * в каком виде бэкенд ждёт город (имя? код? на каком языке?), из контракта
     * не следует, а `City` в приложении хранится собственным id (issue #42) —
     * отправить его наугад значило бы получить пустую выдачу и не понять
     * почему.
     */
    @GET("freelancers")
    suspend fun freelancers(
        @Query("profession") profession: String?,
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ApiResponse<FreelancerPageDto>

    /** Профиль мастера. `id` — uuid. */
    @GET("freelancers/{id}")
    suspend fun freelancer(@Path("id") freelancerId: String): ApiResponse<FreelancerDto>

    /**
     * Услуги мастера — схема `FreelancerServiceResponse`.
     *
     * **Это не `ServiceResponse` барбершопа**, хотя до issue #71 услуги
     * фрилансера разбирались именно им: там `name`/`price`, а здесь
     * `title`/`priceAmount`. Проверено живым ответом стенда 2026-09-09
     * (`GET freelancers/a1000000-…-0001/services`) — с чужим DTO у каждой
     * услуги мастера пропадали название и цена.
     */
    @GET("freelancers/{id}/services")
    suspend fun services(@Path("id") freelancerId: String): ApiResponse<List<FreelancerServiceDto>>

    /** Заказать услугу. Требует Bearer. */
    @POST("freelancers/{id}/orders")
    suspend fun createOrder(
        @Path("id") freelancerId: String,
        @Body body: CreateFreelancerOrderRequest,
    ): ApiResponse<FreelancerOrderDto>

    /** Свои заказы у мастеров, страницами. Требует Bearer. */
    @GET("freelancers/orders/my")
    suspend fun myOrders(
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ApiResponse<FreelancerOrderPageDto>

    // --- Кабинет мастера (issue #71). Всё требует Bearer. ---

    /**
     * Своя анкета. Отдаёт тот же `ProfileResponse`, что и каталог.
     *
     * Анкеты ещё нет — ожидается `404`: у самой ручки других вариантов
     * «пусто» нет, а `GET freelancers/{id}` на неизвестного мастера отвечает
     * именно так (проверено). Под токеном подтвердить нечем — см. риски в
     * `docs/API-CONTRACT.md`.
     */
    @GET("freelancers/me")
    suspend fun myProfile(): ApiResponse<FreelancerDto>

    /**
     * Создать или обновить свою анкету — одна ручка на оба случая
     * (`operationId: upsert`).
     */
    @POST("freelancers/me")
    suspend fun saveMyProfile(@Body body: FreelancerCreateRequest): ApiResponse<FreelancerDto>

    /** Выставить услугу. */
    @POST("freelancers/me/services")
    suspend fun createMyService(
        @Body body: FreelancerServiceRequest,
    ): ApiResponse<FreelancerServiceDto>

    /** Изменить свою услугу. Тело то же, что у создания. */
    @PUT("freelancers/me/services/{serviceId}")
    suspend fun updateMyService(
        @Path("serviceId") serviceId: String,
        @Body body: FreelancerServiceRequest,
    ): ApiResponse<FreelancerServiceDto>

    /**
     * Снять услугу. `ApiResponseVoid` — `data` при успехе `null`, поэтому
     * ответ проверяется `ensureSuccess`, а не `payload`.
     *
     * Удаляет ли бэкенд строку или ставит `isActive: false` — из контракта не
     * следует, и приложение на это не закладывается: список после операции
     * перечитывается у сервера.
     */
    @DELETE("freelancers/me/services/{serviceId}")
    suspend fun deleteMyService(@Path("serviceId") serviceId: String): ApiResponse<JsonElement>

    /**
     * «Принимаю заказы» — переключатель без тела и без ответа: сервер меняет
     * флаг на противоположный сам. Поэтому новое значение приложение не
     * задаёт, а перечитывает анкетой.
     */
    @PUT("freelancers/me/toggle-availability")
    suspend fun toggleAvailability(): ApiResponse<JsonElement>
}

/**
 * Тело `POST /api/v1/freelancers/{id}/orders` — схема `CreateOrderRequest`.
 *
 * На это имя в `/v3/api-docs` ссылается **ровно один** путь (проверено
 * перечислением ссылок), то есть коллизии springdoc здесь нет и поля прочитаны
 * как есть: обязателен только `serviceId`, `address` — `@Size(max = 500)`,
 * `comment` — `@Size(max = 1000)`, `scheduledAt` — `date-time`. Это заметно
 * лучше, чем у записи на время (issue #97), где имя `BookRequest` делят три
 * пути и поля пришлось выводить.
 *
 * Пустые поля уходят **отсутствующими**, а не `null`: в `Json` проекта
 * `explicitNulls = false`.
 *
 * [scheduledAt] — ISO-8601 с зоной (`2026-09-06T10:30:00Z`). Живым запросом
 * форму не подтвердить: `401` приходит **до** валидации тела (проверено и на
 * пустом теле, и на заполненном).
 */
@Serializable
data class CreateFreelancerOrderRequest(
    @SerialName("serviceId") val serviceId: String,
    @SerialName("scheduledAt") val scheduledAt: String? = null,
    @SerialName("address") val address: String? = null,
    @SerialName("comment") val comment: String? = null,
)

/**
 * `ProfileResponse`. Имя в схеме встречается один раз — коллизии нет.
 *
 * Все поля необязательные: отсутствие любого из них — не повод показать экран
 * ошибки вместо каталога.
 *
 * [isAvailable] принимается и под именем `available`: Jackson сериализует
 * `boolean isAvailable` то так, то так, в зависимости от геттера, а ошибка
 * здесь показала бы занятыми всех мастеров подряд (то же правило, что у
 * `isRead` в issue #81 и `isAvailable` в issue #94).
 */
@Serializable
data class FreelancerDto(
    @SerialName("id") val id: String? = null,
    @SerialName("userId") val userId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("profession") val profession: String? = null,
    @SerialName("bio") val bio: String? = null,
    @SerialName("city") val city: String? = null,
    @SerialName("phone") val phone: String? = null,
    @SerialName("hourlyRate") val hourlyRate: Long? = null,
    @SerialName("experienceYears") val experienceYears: Int? = null,
    @SerialName("isAvailable") val isAvailable: Boolean? = null,
    @SerialName("available") val available: Boolean? = null,
    @SerialName("ratingAvg") val ratingAvg: Double? = null,
    @SerialName("ratingCount") val ratingCount: Int? = null,
)

/** `PageResponseProfileResponse`. */
@Serializable
data class FreelancerPageDto(
    @SerialName("content") val content: List<FreelancerDto> = emptyList(),
    @SerialName("page") val page: Int? = null,
    @SerialName("size") val size: Int? = null,
    @SerialName("totalElements") val totalElements: Long? = null,
    @SerialName("totalPages") val totalPages: Int? = null,
    @SerialName("first") val first: Boolean? = null,
    @SerialName("last") val last: Boolean? = null,
)

/**
 * `OrderResponse`.
 *
 * Это имя в схеме перекрыто коллизией springdoc — на `ApiResponseOrderResponse`
 * ссылаются заказы еды, одежды и мастеров, — но коллизию «выиграл» как раз
 * вариант мастера: показанный набор полей содержит `freelancerId`, `serviceId`
 * и `serviceTitle`, которых у заказа еды быть не может. Поэтому поля прочитаны
 * как есть.
 *
 * Даты разбирает `parseServerInstant`: Jackson отдаёт `LocalDateTime` без
 * зоны, и иначе время было бы пустым у всех (issue #53).
 */
@Serializable
data class FreelancerOrderDto(
    @SerialName("id") val id: String? = null,
    @SerialName("freelancerId") val freelancerId: String? = null,
    @SerialName("serviceId") val serviceId: String? = null,
    @SerialName("customerId") val customerId: String? = null,
    @SerialName("serviceTitle") val serviceTitle: String? = null,
    @SerialName("priceAmount") val priceAmount: Long? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("scheduledAt") val scheduledAt: String? = null,
    @SerialName("address") val address: String? = null,
    @SerialName("comment") val comment: String? = null,
    @SerialName("createdAt") val createdAt: String? = null,
)

/**
 * `FreelancerServiceResponse` — услуга мастера.
 *
 * Имя в схеме делят три пути, но все три — про услугу фрилансера
 * (`GET freelancers/{id}/services`, `POST` и `PUT freelancers/me/services`),
 * так что коллизии springdoc здесь нет. Живой ответ стенда 2026-09-09 совпал
 * со схемой поле в поле.
 *
 * [isActive] принимается и под именем `active`: Jackson сериализует
 * `boolean isActive` то так, то так (то же правило, что у `isAvailable` в
 * [FreelancerDto]).
 */
@Serializable
data class FreelancerServiceDto(
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
 * Тело `POST /api/v1/freelancers/me` — схема `FreelancerCreateRequest`.
 *
 * На это имя ссылается ровно один путь, коллизии springdoc нет: обязательны
 * `name` (`@Size(max = 200)`) и `profession` (`max = 100`), необязательны
 * `bio` (`max = 2000`), `city` (`max = 100`), `phone` (`max = 20`),
 * `hourlyRate` и `experienceYears` (оба `int32`, `@Min(0)`).
 *
 * Ручка одна на создание и на правку (`upsert`), и **тело идёт целиком**:
 * незаполненное поле уходит отсутствующим (`explicitNulls = false`), а
 * значит, серверное значение оно, скорее всего, затрёт. Поэтому форма
 * открывается не раньше, чем приедет анкета, — иначе сохранение стёрло бы то,
 * чего человек не видел.
 */
@Serializable
data class FreelancerCreateRequest(
    @SerialName("name") val name: String,
    @SerialName("profession") val profession: String,
    @SerialName("bio") val bio: String? = null,
    @SerialName("city") val city: String? = null,
    @SerialName("phone") val phone: String? = null,
    @SerialName("hourlyRate") val hourlyRate: Int? = null,
    @SerialName("experienceYears") val experienceYears: Int? = null,
)

/**
 * Тело `POST`/`PUT freelancers/me/services` — схема `ServiceRequest`:
 * обязательны `title` (`@Size(max = 200)`) и `priceAmount` (`int64`,
 * `@Min(0)`), необязательны `description` (`max = 2000`) и `durationMinutes`
 * (`int32`, `@Min(0)`).
 *
 * ⚠️ Имя `ServiceRequest` в схеме делят три пути, и третий — чужой
 * (`POST barber-services/places/{placeId}`). Это ровно та коллизия springdoc,
 * из-за которой в issue #97 поля пришлось выводить, а в issue #107 — у
 * `ServiceResponse` — они разошлись с настоящим ответом. Под токеном проверить
 * было нечем (`401` приходит до валидации тела), поэтому тело закреплено
 * тестом: правка после проверки на живом аккаунте будет видна одной строкой.
 */
@Serializable
data class FreelancerServiceRequest(
    @SerialName("title") val title: String,
    @SerialName("priceAmount") val priceAmount: Long,
    @SerialName("description") val description: String? = null,
    @SerialName("durationMinutes") val durationMinutes: Int? = null,
)

/** `PageResponseOrderResponse`. */
@Serializable
data class FreelancerOrderPageDto(
    @SerialName("content") val content: List<FreelancerOrderDto> = emptyList(),
    @SerialName("page") val page: Int? = null,
    @SerialName("size") val size: Int? = null,
    @SerialName("totalElements") val totalElements: Long? = null,
    @SerialName("totalPages") val totalPages: Int? = null,
    @SerialName("first") val first: Boolean? = null,
    @SerialName("last") val last: Boolean? = null,
)
