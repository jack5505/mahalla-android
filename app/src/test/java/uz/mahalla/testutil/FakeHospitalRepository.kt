package uz.mahalla.testutil

import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.booking.domain.Appointment
import uz.mahalla.feature.booking.domain.AppointmentPage
import uz.mahalla.feature.booking.domain.AppointmentStatus
import uz.mahalla.feature.hospital.data.HospitalRepository
import uz.mahalla.feature.hospital.domain.Doctor
import uz.mahalla.feature.hospital.domain.DoctorAppointmentDraft

/**
 * Больницы в памяти (issue #99): экраны проверяются без MockWebServer.
 *
 * Ответ на каждую страницу задаётся отдельно — иначе не отличить догрузку от
 * повторной загрузки первой страницы.
 */
class FakeHospitalRepository : HospitalRepository {

    var doctorsResult: ApiResult<List<Doctor>> = ApiResult.Success(emptyList())

    /** Заведения, у которых спрашивали врачей, — по порядку запросов. */
    val requestedDoctors = mutableListOf<String>()

    var bookResult: ApiResult<Appointment>? = null

    /** Черновики, ушедшие в `book`. */
    val booked = mutableListOf<DoctorAppointmentDraft>()

    val pages: MutableMap<Int, ApiResult<AppointmentPage>> = mutableMapOf()

    var defaultPage: ApiResult<AppointmentPage> = ApiResult.Success(AppointmentPage())

    val requestedPages = mutableListOf<Int>()

    /** Исход отмены; `null` — вернуть ту же запись со статусом «отменена». */
    var cancelResult: ApiResult<Appointment>? = null

    val cancelled = mutableListOf<String>()

    override suspend fun doctors(placeId: String): ApiResult<List<Doctor>> {
        requestedDoctors += placeId
        return doctorsResult
    }

    override suspend fun book(draft: DoctorAppointmentDraft): ApiResult<Appointment> {
        booked += draft
        return bookResult ?: ApiResult.Success(
            Appointment(
                id = "a-1",
                date = draft.date,
                startTime = draft.time,
                status = AppointmentStatus.Pending,
            ),
        )
    }

    override suspend fun myAppointments(page: Int, size: Int): ApiResult<AppointmentPage> {
        requestedPages += page
        return pages[page] ?: defaultPage
    }

    override suspend fun cancel(appointment: Appointment): ApiResult<Appointment> {
        cancelled += appointment.id
        return cancelResult
            ?: ApiResult.Success(appointment.copy(status = AppointmentStatus.Cancelled))
    }
}
