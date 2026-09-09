package uz.mahalla.feature.activity.data

import retrofit2.http.GET
import retrofit2.http.Query
import uz.mahalla.data.network.ApiResponse
import uz.mahalla.feature.booking.data.AppointmentPageDto
import uz.mahalla.feature.cinema.data.CinemaTicketPageDto
import uz.mahalla.feature.fashion.data.OrderPageDto
import uz.mahalla.feature.gaming.data.GamingBookingPageDto

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
 * **Своих DTO у этого API нет вовсе.** Все пять ответов уже описаны в
 * вертикалях, которые ходят в те же ручки: [OrderPageDto] у одежды,
 * [AppointmentPageDto] у записи к мастеру (её же переиспользует больница),
 * [CinemaTicketPageDto] у кино и [GamingBookingPageDto] у игровых зон
 * (issue #98 — приехала в `main` последней). У бэкенда это буквально одна
 * модель на путь, и вторая копия разъехалась бы с первой при первой же правке
 * контракта — как оно и вышло с `LocalTime` (см. `AppointmentDto`).
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
     * Записи к врачу. Схема ответа — **своя**: сверка со стендом 2026-09-09
     * показала, что общей `AppointmentResponse` больше нет, коллизия springdoc
     * разошлась на `AppointmentBookingResponse` у мастера и
     * `HospitalAppointmentResponse` у врача. Вторая беднее: вместо
     * `placeId`/`serviceName`/`price`/`endTime` в ней `doctorId` и `complaint`.
     *
     * Читается всё равно одним [AppointmentPageDto] — так же, как это делает
     * сама вертикаль больницы (`HospitalApi`, issue #99). Полей больше, чем
     * приедет, но все они необязательные, а лишние `doctorId`/`complaint`
     * пропускает `ignoreUnknownKeys`. Практическая разница одна: у записи к
     * врачу не будет суммы — её бэкенд в этом ответе не отдаёт.
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
