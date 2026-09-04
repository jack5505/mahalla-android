package uz.mahalla.feature.hospital.data

import uz.mahalla.core.format.toServerTime
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
import java.time.Clock
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
 * обе вертикали записи: у бэкенда это одна модель (`AppointmentResponse`) и
 * одна ручка отмены.
 *
 * Интерфейс — ради тестов ViewModel: экраны проверяются без MockWebServer.
 */
interface HospitalRepository : AppointmentsSource {

    /** Врачи заведения. */
    suspend fun doctors(placeId: String): ApiResult<List<Doctor>>

    /**
     * Записаться к врачу. Черновик проверяется до запроса — см.
     * [DefaultHospitalRepository.book].
     */
    suspend fun book(draft: DoctorAppointmentDraft): ApiResult<Appointment>
}

@Singleton
class DefaultHospitalRepository @Inject constructor(
    private val api: HospitalApi,
    private val clock: Clock,
) : HospitalRepository {

    override suspend fun doctors(placeId: String): ApiResult<List<Doctor>> =
        apiCall { api.doctors(placeId).payload() }
            .map { doctors -> doctors.mapNotNull(DoctorDto::toDomain) }

    /**
     * Незаполненный черновик и прошедшее время в сеть не уходят: сервер ответил
     * бы тем же отказом, но платой были бы запрос и молчание экрана на время
     * его выполнения.
     *
     * Ответ без `id` отказом **не** считается — запись создана, а увидеть её
     * можно в «моих записях» (см. `AppointmentDto.toCreated`). Это разница с
     * талоном очереди (issue #96), где ручки чтения нет вовсе и такой ответ
     * приходится считать негодным.
     */
    override suspend fun book(draft: DoctorAppointmentDraft): ApiResult<Appointment> {
        val doctorId = draft.doctorId
        val date = draft.date
        val time = draft.time
        if (!draft.canSubmit || doctorId == null || date == null || time == null) {
            return ApiResult.Failure(
                ApiError.Business(BookingRepository.INVALID_REQUEST_CODE),
            )
        }
        if (BookingSlots.startsAt(date, time).isBefore(clock.instant())) {
            return ApiResult.Failure(
                ApiError.Business(BookingRepository.INVALID_REQUEST_CODE),
            )
        }

        return apiCall {
            api.book(
                BookDoctorRequest(
                    doctorId = doctorId,
                    date = date.toString(),
                    startTime = time.toServerTime(),
                    complaint = draft.complaintOrNull(),
                ),
            ).payload()
        }.map(AppointmentDto::toCreated)
    }

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
