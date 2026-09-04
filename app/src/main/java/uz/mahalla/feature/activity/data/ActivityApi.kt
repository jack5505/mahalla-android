package uz.mahalla.feature.activity.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query
import uz.mahalla.data.network.ApiResponse
import uz.mahalla.feature.food.data.OrderViewDto

/**
 * «Мои активности» (issue #73, задача T7): пять источников, из которых
 * собирается один список.
 *
 * Контракт снят со стенда (`/v3/api-docs` + прямые curl'ы; дизайн-репо агенту
 * недоступно). Все пять ручек требуют Bearer — без токена приходит
 * `401 UNAUTHORIZED`, — поэтому API создаётся на **основном** Retrofit, а не
 * на «голом» `@RefreshClient`. Гео-заголовки бэкенду тоже нужны, но их ставит
 * `GeoHeaderInterceptor` на обоих клиентах (issue #53).
 *
 * Пагинация у всех пяти настоящая и одинаковая: `page` + `size`, ответ —
 * конверт вокруг `PageResponse…` с `content`/`page`/`totalPages`/`last`.
 *
 * **Почему заказы читаются общей ручкой.** `GET orders` отдаёт `OrderView` —
 * ту же схему, по которой экран статуса читает один заказ (issue #9), и в ней
 * есть все суммы. У `food/orders/my` и `fashion/orders/my` ответ описан
 * схемой `OrderResponse`, а это имя в `/v3/api-docs` перекрыто коллизией
 * springdoc: под ним лежит заказ **фрилансера** (`freelancerId`,
 * `serviceTitle`), то есть имена полей оттуда взять нельзя. Плюс один запрос
 * вместо трёх: `vertical` не передаётся, и приезжают заказы всех вертикалей
 * сразу — `FOOD`, `CLOTHING`, `PHARMACY`, `CINEMA`, `GAMING`.
 */
interface ActivityApi {

    /**
     * Заказы всех вертикалей. `vertical` и `status` намеренно не передаются:
     * фильтр «активные / история» работает на клиенте по уже приехавшему
     * списку, потому что «активное» — это набор статусов, а параметр `status`
     * принимает ровно один.
     */
    @GET("orders")
    suspend fun orders(
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ApiResponse<OrderPageDto>

    @GET("gaming/bookings/my")
    suspend fun gamingBookings(
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ApiResponse<GamingBookingPageDto>

    /** Записи к мастеру (`appointment-controller`). */
    @GET("appointments/my")
    suspend fun masterAppointments(
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ApiResponse<AppointmentPageDto>

    /**
     * Записи к врачу. Ручка отдельная, а схема ответа — та же
     * `AppointmentResponse`, что у мастера: различает их только источник.
     */
    @GET("hospitals/appointments/my")
    suspend fun doctorAppointments(
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ApiResponse<AppointmentPageDto>

    @GET("cinema/tickets/my")
    suspend fun cinemaTickets(
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ApiResponse<CinemaTicketPageDto>
}

/**
 * Страница ответа. Общая для всех пяти источников — `PageResponse…` у них
 * отличается только типом `content`.
 *
 * Все поля необязательные: отсутствие любого из них не повод показать ошибку
 * вместо списка.
 */
@Serializable
data class ActivityPageDto<T>(
    @SerialName("content") val content: List<T> = emptyList(),
    @SerialName("page") val page: Int? = null,
    @SerialName("size") val size: Int? = null,
    @SerialName("totalElements") val totalElements: Long? = null,
    @SerialName("totalPages") val totalPages: Int? = null,
    @SerialName("first") val first: Boolean? = null,
    @SerialName("last") val last: Boolean? = null,
)

// Псевдонимы — только ради читаемости подписей выше: раскрываются они на
// этапе компиляции, и сериализатор видит полный параметризованный тип.
typealias OrderPageDto = ActivityPageDto<OrderViewDto>
typealias GamingBookingPageDto = ActivityPageDto<GamingBookingDto>
typealias AppointmentPageDto = ActivityPageDto<AppointmentDto>
typealias CinemaTicketPageDto = ActivityPageDto<CinemaTicketDto>

/**
 * `GamingBooking` бэкенда: бронь игровой зоны.
 *
 * Названия заведения в ответе нет — только `placeId`, как и во всех остальных
 * четырёх источниках.
 */
@Serializable
data class GamingBookingDto(
    @SerialName("id") val id: String? = null,
    @SerialName("placeId") val placeId: String? = null,
    @SerialName("zoneId") val zoneId: String? = null,
    @SerialName("startTime") val startTime: String? = null,
    @SerialName("endTime") val endTime: String? = null,
    /**
     * Длительность брони в часах. Объявлена, потому что документирует
     * контракт, но в домен не доезжает: подпись «2 ч» обязана быть
     * локализуемой строкой с plurals, а в списке у брони и так есть время
     * начала, статус и сумма. Просится на экран брони, когда он появится.
     */
    @SerialName("durationHours") val durationHours: Int? = null,
    @SerialName("totalPrice") val totalPrice: Long? = null,
    /** `CONFIRMED` / `ACTIVE` / `COMPLETED` / `CANCELLED`. */
    @SerialName("status") val status: String? = null,
    @SerialName("createdAt") val createdAt: String? = null,
)

/**
 * `AppointmentResponse` бэкенда: запись к мастеру или к врачу.
 *
 * `apptDate` — дата без времени (`2026-09-10`), `startTime` — объект
 * `LocalTime` (`{hour, minute, second, nano}`), а не строка: Jackson
 * сериализует `java.time.LocalTime` полями, если для него не настроен
 * `JavaTimeModule`. Собирает их в момент времени `ActivityMappers`.
 */
@Serializable
data class AppointmentDto(
    @SerialName("id") val id: String? = null,
    @SerialName("placeId") val placeId: String? = null,
    @SerialName("serviceId") val serviceId: String? = null,
    /** Единственное человекочитаемое поле во всём ответе. */
    @SerialName("serviceName") val serviceName: String? = null,
    @SerialName("price") val price: Long? = null,
    @SerialName("apptDate") val apptDate: String? = null,
    @SerialName("startTime") val startTime: LocalTimeDto? = null,
    @SerialName("endTime") val endTime: LocalTimeDto? = null,
    /** `PENDING` / `CONFIRMED` / `CANCELLED` / `COMPLETED` / `NO_SHOW`. */
    @SerialName("status") val status: String? = null,
    @SerialName("createdAt") val createdAt: String? = null,
)

/** `LocalTime` бэкенда — объект, а не строка. */
@Serializable
data class LocalTimeDto(
    @SerialName("hour") val hour: Int? = null,
    @SerialName("minute") val minute: Int? = null,
    @SerialName("second") val second: Int? = null,
    @SerialName("nano") val nano: Int? = null,
)

/** `CinemaTicket` бэкенда: билет в кино. */
@Serializable
data class CinemaTicketDto(
    @SerialName("id") val id: String? = null,
    @SerialName("sessionId") val sessionId: String? = null,
    @SerialName("seatNumber") val seatNumber: String? = null,
    @SerialName("price") val price: Long? = null,
    /** `ACTIVE` / `USED` / `CANCELLED` / `REFUNDED`. */
    @SerialName("status") val status: String? = null,
    @SerialName("createdAt") val createdAt: String? = null,
)
