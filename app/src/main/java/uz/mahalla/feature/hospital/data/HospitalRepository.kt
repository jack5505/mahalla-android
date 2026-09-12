package uz.mahalla.feature.hospital.data

import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.map
import uz.mahalla.data.network.ensureSuccess
import uz.mahalla.data.network.payload
import uz.mahalla.feature.booking.data.AppointmentDto
import uz.mahalla.feature.booking.data.AppointmentPageDto
import uz.mahalla.feature.booking.data.AppointmentsSource
import uz.mahalla.feature.booking.data.BookingRepository
import uz.mahalla.feature.booking.data.toCreated
import uz.mahalla.feature.booking.data.toDomain
import uz.mahalla.feature.booking.domain.Appointment
import uz.mahalla.feature.booking.domain.AppointmentPage
import uz.mahalla.feature.booking.domain.AppointmentStatus
import uz.mahalla.feature.booking.domain.BookingSlots
import uz.mahalla.feature.hospital.domain.Doctor
import uz.mahalla.feature.hospital.domain.DoctorAppointmentDraft
import uz.mahalla.feature.hospital.domain.DoctorSlot
import uz.mahalla.feature.hospital.domain.DoctorSlots
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Вертикаль «Больницы» (issue #99): врачи, запись, свои записи, отмена.
 *
 * Кэша нет намеренно — ни у врачей, ни у записей: состав врачей меняет
 * заведение, а статус записи меняет оно же (`PUT appointments/{id}/status`,
 * бизнес-панель эпика #16), и `PENDING` из Room после подтверждения был бы
 * прямой ложью.
 *
 * [AppointmentsSource] реализуется затем, чтобы экран «мои записи» был один на
 * обе вертикали записи. Общий у них только вид записи на экране: на бэкенде это
 * разные сущности со своими ручками — с 2026-09-09 у больниц есть и своя
 * отмена, `POST hospitals/appointments/{id}/cancel` (issue #167).
 *
 * Интерфейс — ради тестов ViewModel: экраны проверяются без MockWebServer.
 */
interface HospitalRepository : AppointmentsSource {

    /** Врачи заведения. */
    suspend fun doctors(placeId: String): ApiResult<List<Doctor>>

    /** Карточка врача (issue #181). */
    suspend fun doctor(doctorId: String): ApiResult<Doctor>

    /**
     * Свободные слоты врача на день (issue #181) — так, как их отдал сервер,
     * минус уже наступившие ([DoctorSlots.available]).
     */
    suspend fun slots(doctorId: String, date: LocalDate): ApiResult<List<DoctorSlot>>

    /**
     * Записаться к врачу. Черновик проверяется до запроса — см.
     * [DefaultHospitalRepository.book].
     */
    suspend fun book(draft: DoctorAppointmentDraft): ApiResult<Appointment>

    /**
     * Карточка записи к врачу (issue #181). Ручка объявлена по контракту, но
     * пока не используется ни одним экраном — карточка записи целиком
     * отдельная задача (#183).
     */
    suspend fun appointment(appointmentId: String): ApiResult<Appointment>
}

@Singleton
class DefaultHospitalRepository @Inject constructor(
    private val api: HospitalApi,
    private val clock: Clock,
) : HospitalRepository {

    override suspend fun doctors(placeId: String): ApiResult<List<Doctor>> =
        apiCall { api.doctors(placeId).payload() }
            .map { doctors -> doctors.mapNotNull(DoctorDto::toDomain) }

    override suspend fun doctor(doctorId: String): ApiResult<Doctor> =
        apiCall { api.doctor(doctorId).payload() }.map { dto -> dto.toDomain(doctorId) }

    /**
     * Прошедшее время отсеивается **после** ответа сервера, а не вместо него:
     * какие слоты заняты, знает только он, а какие уже наступили — знают оба.
     */
    override suspend fun slots(doctorId: String, date: LocalDate): ApiResult<List<DoctorSlot>> =
        apiCall { api.slots(doctorId = doctorId, date = date.toString()).payload() }
            .map { raw -> DoctorSlots.available(raw = raw, date = date, now = clock.instant()) }

    /**
     * Незаполненный черновик и прошедшее время в сеть не уходят: сервер ответил
     * бы тем же отказом, но платой были бы запрос и молчание экрана на время
     * его выполнения.
     *
     * [DoctorSlot.raw] уходит в `startTime` без разбора и повторной сборки —
     * ровно та строка, что пришла из `slots()` (issue #181, #144).
     *
     * Ответ без `id` отказом **не** считается — запись создана, а увидеть её
     * можно в «моих записях» (см. `AppointmentDto.toCreated`). Это разница с
     * талоном очереди (issue #96), где ручки чтения нет вовсе и такой ответ
     * приходится считать негодным.
     */
    override suspend fun book(draft: DoctorAppointmentDraft): ApiResult<Appointment> {
        val doctorId = draft.doctorId
        val date = draft.date
        val slot = draft.slot
        if (!draft.canSubmit || doctorId == null || date == null || slot == null) {
            return ApiResult.Failure(
                ApiError.Business(BookingRepository.INVALID_REQUEST_CODE),
            )
        }
        if (BookingSlots.startsAt(date, slot.time).isBefore(clock.instant())) {
            return ApiResult.Failure(
                ApiError.Business(BookingRepository.INVALID_REQUEST_CODE),
            )
        }

        return apiCall {
            api.book(
                BookDoctorRequest(
                    doctorId = doctorId,
                    date = date.toString(),
                    startTime = slot.raw,
                    complaint = draft.complaintOrNull(),
                ),
            ).payload()
        }.map(AppointmentDto::toCreated)
    }

    override suspend fun appointment(appointmentId: String): ApiResult<Appointment> =
        apiCall { api.appointment(appointmentId).payload() }.map(AppointmentDto::toCreated)

    override suspend fun myAppointments(page: Int, size: Int): ApiResult<AppointmentPage> =
        apiCall { api.myAppointments(page = page.coerceAtLeast(0), size = size).payload() }
            .map(AppointmentPageDto::toDomain)

    /**
     * Ответ на отмену — та же запись, но обязательным его разбор не считаем:
     * `ensureSuccess()` уже подтвердил, что сервер отменил именно её. Если
     * годного тела не окажется, состояние выводится из факта отмены — иначе
     * удачная отмена выглядела бы как «отменить не удалось» (та же грабля, что
     * у заказов еды, issue #9, и у талона очереди, issue #96).
     */
    override suspend fun cancel(appointment: Appointment): ApiResult<Appointment> {
        if (appointment.id.isBlank()) {
            return ApiResult.Failure(
                ApiError.Business(BookingRepository.INVALID_REQUEST_CODE),
            )
        }

        return apiCall {
            val response = api.cancel(appointment.id)
            response.ensureSuccess()
            response.data
        }.map { dto ->
            dto?.toDomain() ?: appointment.copy(status = AppointmentStatus.Cancelled)
        }
    }
}
