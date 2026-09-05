package uz.mahalla.feature.freelancer.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import uz.mahalla.data.network.ApiResponse
import uz.mahalla.feature.booking.data.ServiceDto

/**
 * Вертикаль «Мастера» (issue #107): каталог фрилансеров, их услуги и заказы.
 *
 * Контракт снят со стенда (`/v3/api-docs` + прямые curl'ы 2026-09-04).
 * Проверено живыми запросами:
 *
 * | запрос | ответ |
 * |---|---|
 * | `GET freelancers` с гео-заголовками | `200`, `content: []` — каталог пуст |
 * | `GET freelancers` без гео-заголовков | `403 GEO_PERMISSION_REQUIRED` |
 * | `GET freelancers/{uuid}` | `404 NOT_FOUND` «Profil topilmadi» |
 * | `GET freelancers/1` | `400 TYPE_MISMATCH` — `id` это **uuid** |
 * | `GET freelancers/orders/my`, `POST freelancers/{id}/orders` | `401` |
 *
 * Гео-заголовки ставит `GeoHeaderInterceptor` на обоих клиентах (issue #53),
 * так что вопрос закрыт сам собой.
 *
 * **Каталог, профиль и услуги анонимны**, а всё, что про заказ, требует Bearer.
 * Разделять API по двум Retrofit из-за этого незачем: основной клиент просто
 * добавит заголовок, который читающим ручкам не мешает, — а «голый»
 * `@RefreshClient` сломал бы заказ. Поэтому API целиком собирается на
 * **основном** Retrofit.
 *
 * **Кабинет самого мастера** (ветка `freelancers/me`,
 * `PUT freelancers/orders/{orderId}/status`) сюда не входит намеренно: issue
 * #107 прямо оставляет его бизнес-панели (эпик #16).
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
     * Услуги мастера. `data` — массив `ServiceResponse`, то есть **та же
     * схема**, что у `barber-services` (issue #97): у неё и поле называется
     * `freelancerId`. DTO поэтому переиспользуется — у бэкенда это одна
     * модель, и вторая её копия разъехалась бы с первой при первой же правке
     * контракта.
     */
    @GET("freelancers/{id}/services")
    suspend fun services(@Path("id") freelancerId: String): ApiResponse<List<ServiceDto>>

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
