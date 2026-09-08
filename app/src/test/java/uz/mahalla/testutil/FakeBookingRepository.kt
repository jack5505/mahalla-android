package uz.mahalla.testutil

import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.booking.data.BookingRepository
import uz.mahalla.feature.booking.domain.Appointment
import uz.mahalla.feature.booking.domain.AppointmentPage
import uz.mahalla.feature.booking.domain.AppointmentStatus
import uz.mahalla.feature.booking.domain.BarberService
import java.time.LocalDate
import java.time.LocalTime

/**
 * Бронирование в памяти (issue #97): экраны проверяются без MockWebServer.
 *
 * Ответ на каждую пару «услуга + день» и на каждую страницу задаётся отдельно
 * — иначе не отличить догрузку от повторной загрузки первой страницы, а слоты
 * одного дня от слотов другого.
 */
class FakeBookingRepository : BookingRepository {

    var servicesResult: ApiResult<List<BarberService>> = ApiResult.Success(emptyList())

    /** Слоты по паре «услуга + день»; иначе [defaultSlots]. */
    val slotsByRequest: MutableMap<Pair<String, LocalDate>, ApiResult<List<LocalTime>>> =
        mutableMapOf()

    var defaultSlots: ApiResult<List<LocalTime>> = ApiResult.Success(emptyList())

    /** Что именно спрашивали: услуга и день — по порядку запросов. */
    val requestedSlots = mutableListOf<Pair<String, LocalDate>>()

    var bookResult: ApiResult<Appointment>? = null

    /** Что ушло в `book`: заведение, услуга, день, время. */
    val booked = mutableListOf<BookedRequest>()

    val pages: MutableMap<Int, ApiResult<AppointmentPage>> = mutableMapOf()

    var defaultPage: ApiResult<AppointmentPage> = ApiResult.Success(AppointmentPage())

    val requestedPages = mutableListOf<Int>()

    /** Исход отмены; `null` — вернуть ту же запись со статусом «отменена». */
    var cancelResult: ApiResult<Appointment>? = null

    val cancelled = mutableListOf<String>()

    data class BookedRequest(
        val placeId: String,
        val serviceId: String,
        val date: LocalDate,
        val time: LocalTime,
    )

    override suspend fun services(placeId: String): ApiResult<List<BarberService>> = servicesResult

    override suspend fun slots(
        placeId: String,
        serviceId: String,
        date: LocalDate,
    ): ApiResult<List<LocalTime>> {
        requestedSlots += serviceId to date
        return slotsByRequest[serviceId to date] ?: defaultSlots
    }

    override suspend fun book(
        placeId: String,
        serviceId: String,
        date: LocalDate,
        time: LocalTime,
    ): ApiResult<Appointment> {
        booked += BookedRequest(placeId, serviceId, date, time)
        return bookResult ?: ApiResult.Success(
            Appointment(
                id = "a-1",
                placeId = placeId,
                serviceId = serviceId,
                date = date,
                startTime = time,
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
