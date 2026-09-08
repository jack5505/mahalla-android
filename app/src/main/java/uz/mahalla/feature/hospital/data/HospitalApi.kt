package uz.mahalla.feature.hospital.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import uz.mahalla.data.network.ApiResponse
import uz.mahalla.feature.booking.data.AppointmentDto
import uz.mahalla.feature.booking.data.AppointmentPageDto

/**
 * Вертикаль «Больницы» (эпик #11, issue #99): врачи заведения и запись к ним.
 *
 * Контракт снят со стенда (`/v3/api-docs` + прямые curl'ы 2026-09-04).
 * `placeId` — **uuid**: числовой id отвечает `400 TYPE_MISMATCH` (проверено).
 * Гео-заголовки обязательны на всех путях, включая анонимный список врачей
 * (без них `403 GEO_PERMISSION_REQUIRED` — проверено), но их ставит
 * `GeoHeaderInterceptor` на обоих клиентах (issue #53).
 *
 * **Список врачей анонимен** (`200` без токена, `data: []` на пустом
 * каталоге), а всё, что про саму запись, требует Bearer (`401`). Разделять API
 * по двум Retrofit из-за этого незачем: основной клиент просто добавит
 * заголовок, который читающей ручке не мешает, — а «голый» `@RefreshClient`
 * сломал бы запись. Поэтому API целиком собирается на **основном** Retrofit.
 *
 * Ответы записи — те же `AppointmentResponse` и
 * `PageResponseAppointmentResponse`, что у брони (issue #97), поэтому DTO
 * переиспользуются: у бэкенда это буквально одна модель, и вторая её копия
 * разъехалась бы с первой при первой же правке контракта.
 */
interface HospitalApi {

    /** Врачи заведения. `data` — массив `DoctorResponse`. */
    @GET("hospitals/places/{placeId}/doctors")
    suspend fun doctors(@Path("placeId") placeId: String): ApiResponse<List<DoctorDto>>

    /** Записаться к врачу. Требует Bearer. */
    @POST("hospitals/appointments")
    suspend fun book(@Body body: BookDoctorRequest): ApiResponse<AppointmentDto>

    /** Свои записи к врачам, страницами. Требует Bearer. */
    @GET("hospitals/appointments/my")
    suspend fun myAppointments(
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ApiResponse<AppointmentPageDto>

    /**
     * Отмена. **Своей отмены у `hospitals` нет** — в контроллере всего четыре
     * пути, — поэтому берётся общая ручка записи. Она объявлена над той же
     * схемой `AppointmentResponse`, что и запись к врачу, то есть на бэкенде
     * это одна сущность; проверить это под токеном в CI нечем (`401` приходит
     * до маршрутизации), и расхождение попадёт в отчёт отдельным риском.
     */
    @POST("appointments/{id}/cancel")
    suspend fun cancel(@Path("id") appointmentId: String): ApiResponse<AppointmentDto>
}

/**
 * Тело `POST /api/v1/hospitals/appointments` — схема `BookRequest`.
 *
 * Имя `BookRequest` в `/v3/api-docs` перекрыто коллизией springdoc (на него
 * ссылаются `appointments`, `gaming/bookings` и `hospitals/appointments`), но
 * здесь это **не мешает**: показанный набор полей —
 * `{doctorId, date, startTime, complaint}` — и есть больничный, то есть
 * коллизию «выиграл» как раз этот путь. Обязательны `doctorId`, `date`,
 * `startTime`; `complaint` — `@Size(max = 1000)`.
 *
 * [startTime] уходит строкой `HH:mm:ss`, хотя springdoc описывает `LocalTime`
 * объектом `{hour, minute, second, nano}`: так его читает Jackson с
 * `JavaTimeModule`, и так же отправляет бронь (issue #97). Живым запросом это
 * не проверить — `401` приходит **до** валидации тела (проверено и на пустом
 * теле, и на заполненном, и с мусорным Bearer).
 *
 * Пустая жалоба уходит **отсутствующим** полем, а не `null`: в `Json` проекта
 * `explicitNulls = false`.
 */
@Serializable
data class BookDoctorRequest(
    @SerialName("doctorId") val doctorId: String,
    /** `yyyy-MM-dd`. */
    @SerialName("date") val date: String,
    /** `HH:mm:ss`. */
    @SerialName("startTime") val startTime: String,
    @SerialName("complaint") val complaint: String? = null,
)

/**
 * `DoctorResponse`. Имя в схеме встречается один раз — коллизии нет, поля
 * прочитаны как есть.
 *
 * Все поля необязательные: отсутствие любого из них — не повод показать экран
 * ошибки вместо списка врачей.
 */
@Serializable
data class DoctorDto(
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("specialty") val specialty: String? = null,
    @SerialName("bio") val bio: String? = null,
    @SerialName("consultationPrice") val consultationPrice: Long? = null,
)
