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
import uz.mahalla.feature.hospital.domain.DoctorSlot

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
 * Ответы записи разбираются теми же DTO, что у брони (issue #97), но **не
 * потому, что модель одна**: в схеме от 2026-09-09 у больниц свой
 * `HospitalAppointmentResponse`, у брони — `AppointmentBookingResponse`
 * (issue #167). Общего в них хватает на всё, что показывает экран
 * (`id`, `apptDate`, `startTime`, `status`, `createdAt`), поэтому DTO пока
 * один; `doctorId` и `complaint` больничного ответа в него не входят и
 * теряются (issue #219).
 */
interface HospitalApi {

    /** Врачи заведения. `data` — массив `DoctorResponse`. */
    @GET("hospitals/places/{placeId}/doctors")
    suspend fun doctors(@Path("placeId") placeId: String): ApiResponse<List<DoctorDto>>

    /** Карточка врача (issue #181). `data` — `DoctorResponse`, та же схема, что и в списке. */
    @GET("hospitals/doctors/{id}")
    suspend fun doctor(@Path("id") doctorId: String): ApiResponse<DoctorDto>

    /**
     * Свободные слоты врача на день (issue #181, найдено сверкой в #92).
     * `data` — массив строк (`ApiResponseListString`), как у слотов брони
     * (issue #97): сервер отдаёт готовое время (`"09:00"`/`"09:00:00"`),
     * занятое в него уже не попадает. `date` обязателен, `yyyy-MM-dd`.
     */
    @GET("hospitals/doctors/{id}/slots")
    suspend fun slots(
        @Path("id") doctorId: String,
        @Query("date") date: String,
    ): ApiResponse<List<String>>

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
     * Карточка записи к врачу (issue #181). `data` — `HospitalAppointmentResponse`,
     * разбирается тем же [AppointmentDto], что и остальные ответы вертикали —
     * `doctorId` и `complaint` из неё теряются (issue #219). Экран, который эту
     * ручку показывает, — отдельная задача (#183); здесь ручка только объявлена.
     */
    @GET("hospitals/appointments/{id}")
    suspend fun appointment(@Path("id") appointmentId: String): ApiResponse<AppointmentDto>

    /**
     * Отмена записи к врачу — **своей ручкой больниц** (issue #167).
     *
     * До 2026-09-09 её здесь не было: в `hospital-controller` было четыре пути,
     * и отмена уходила в общую `POST appointments/{id}/cancel`. В схеме от
     * 2026-09-09 путь `POST /api/v1/hospitals/appointments/{id}/cancel` есть, и
     * заодно рассосалась коллизия springdoc, из-за которой обе вертикали
     * выглядели одной сущностью: у больниц теперь `HospitalAppointmentResponse`
     * (`{id, doctorId, apptDate, startTime, complaint, status, createdAt}`), у
     * брони — `AppointmentBookingResponse` (`{id, placeId, userId, serviceId,
     * serviceName, price, apptDate, startTime, endTime, status, createdAt}`).
     * Разные схемы у создания и у чтения — значит, и записи разные, а общая
     * ручка чужую отменить не сможет.
     *
     * Живой пробой это не доказать: `401` приходит до маршрутизации (оба пути
     * отвечают им одинаково, проверено 2026-09-10), а `CONTRACT_REFRESH_TOKEN`
     * в CI не задан. Ответ разбирается тем же [AppointmentDto] — общих полей
     * хватает, а лишние `kotlinx.serialization` игнорирует.
     */
    @POST("hospitals/appointments/{id}/cancel")
    suspend fun cancel(@Path("id") appointmentId: String): ApiResponse<AppointmentDto>
}

/**
 * Тело `POST /api/v1/hospitals/appointments` — схема `HospitalBookRequest`.
 *
 * В схеме 2026-09-04 она называлась `BookRequest` и была перекрыта коллизией
 * springdoc (на имя ссылались `appointments`, `gaming/bookings` и
 * `hospitals/appointments`); коллизию «выиграл» как раз больничный путь, и
 * поля брались оттуда. В схеме 2026-09-09 коллизии больше нет, и собственная
 * `HospitalBookRequest` подтверждает тот же набор:
 * `{doctorId, date, startTime, complaint}`, обязательны `doctorId`, `date`,
 * `startTime`, у `complaint` — `maxLength: 1000`.
 *
 * [startTime] уходит строкой, хотя springdoc описывает `LocalTime` объектом
 * `{hour, minute, second, nano}`: так его читает Jackson с `JavaTimeModule`.
 * Живым запросом это не проверить — `401` приходит **до** валидации тела
 * (проверено и на пустом теле, и на заполненном, и с мусорным Bearer).
 *
 * **В отличие от брони** (issue #97, там `LocalTime.toServerTime()` всегда
 * собирает `HH:mm:ss`), здесь строка — ровно [DoctorSlot.raw] выбранного
 * слота, без разбора и повторной сборки (issue #181, риск из issue #144: там
 * лишний шаг «разобрали → собрали заново» стоил вертикали брони пяти часов
 * расхождения между UTC и Asia/Tashkent).
 *
 * Пустая жалоба уходит **отсутствующим** полем, а не `null`: в `Json` проекта
 * `explicitNulls = false`.
 */
@Serializable
data class BookDoctorRequest(
    @SerialName("doctorId") val doctorId: String,
    /** `yyyy-MM-dd`. */
    @SerialName("date") val date: String,
    /** Ровно [DoctorSlot.raw] выбранного слота — без разбора и повторной сборки. */
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
