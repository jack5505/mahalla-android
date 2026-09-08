package uz.mahalla.feature.activity.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query
import uz.mahalla.data.network.ApiResponse
import uz.mahalla.feature.booking.data.AppointmentPageDto
import uz.mahalla.feature.cinema.data.CinemaTicketPageDto
import uz.mahalla.feature.fashion.data.OrderPageDto

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
 * **Свои DTO здесь только у игровых зон.** Четыре из пяти ответов уже описаны
 * в вертикалях, которые ходят в те же ручки: [OrderPageDto] у одежды,
 * [AppointmentPageDto] у записи к мастеру (её же переиспользует больница) и
 * [CinemaTicketPageDto] у кино. У бэкенда это буквально одна модель на путь, и
 * вторая копия разъехалась бы с первой при первой же правке контракта — как
 * оно и вышло с `LocalTime` (см. `AppointmentDto`).
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
