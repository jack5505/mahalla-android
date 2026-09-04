package uz.mahalla.feature.booking.data

import uz.mahalla.core.format.toServerTime
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.map
import uz.mahalla.data.network.ensureSuccess
import uz.mahalla.data.network.payload
import uz.mahalla.feature.booking.domain.Appointment
import uz.mahalla.feature.booking.domain.AppointmentPage
import uz.mahalla.feature.booking.domain.AppointmentStatus
import uz.mahalla.feature.booking.domain.BarberService
import uz.mahalla.feature.booking.domain.BookingSlots
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Бронирование по времени (issue #97): услуги, слоты, запись, отмена.
 *
 * Кэша нет намеренно — ни у слотов, ни у записей. Слот, свободный десять минут
 * назад, мог уже уйти, а показанный из Room занятый слот кончился бы отказом
 * сервера на подтверждении; статус записи меняет заведение, и `PENDING` из
 * кэша после подтверждения был бы прямой ложью.
 *
 * Интерфейс — ради тестов ViewModel: экраны проверяются без MockWebServer.
 */
interface BookingRepository {

    /**
     * Услуги, на которые записывают. Выключенные (`isActive: false`) в список
     * не попадают: записаться на них нельзя, а строка, которая ничего не
     * делает, читается как сломанная.
     */
    suspend fun services(placeId: String): ApiResult<List<BarberService>>

    /**
     * Свободные слоты на день — так, как их отдал сервер, минус уже
     * наступившие (`BookingSlots.available`).
     */
    suspend fun slots(
        placeId: String,
        serviceId: String,
        date: LocalDate,
    ): ApiResult<List<LocalTime>>

    /** Записаться. */
    suspend fun book(
        placeId: String,
        serviceId: String,
        date: LocalDate,
        time: LocalTime,
    ): ApiResult<Appointment>

    /** Свои записи, страницами. */
    suspend fun myAppointments(page: Int = 0, size: Int = PAGE_SIZE): ApiResult<AppointmentPage>

    /**
     * Отменить свою запись. Возвращается состояние после отмены — либо из
     * ответа сервера, либо выведенное из самого факта успешной отмены.
     */
    suspend fun cancel(appointment: Appointment): ApiResult<Appointment>

    companion object {
        /** Код отказа, когда записываться нечем ещё до запроса. */
        const val INVALID_REQUEST_CODE = "APPOINTMENT_REQUEST_INVALID"

        /** Столько же по умолчанию берёт и сам бэкенд. */
        const val PAGE_SIZE = 20
    }
}

@Singleton
class DefaultBookingRepository @Inject constructor(
    private val api: BookingApi,
    private val clock: Clock,
) : BookingRepository {

    override suspend fun services(placeId: String): ApiResult<List<BarberService>> =
        apiCall { api.services(placeId).payload() }
            .map { services -> services.mapNotNull(ServiceDto::toDomain).filter { it.isActive } }

    /**
     * Прошедшее время отсеивается **после** ответа сервера, а не вместо него:
     * какие слоты заняты, знает только он, а какие уже наступили — знают оба, и
     * предлагать сегодняшнее «в девять утра» в полдень нельзя.
     */
    override suspend fun slots(
        placeId: String,
        serviceId: String,
        date: LocalDate,
    ): ApiResult<List<LocalTime>> =
        apiCall {
            api.slots(
                placeId = placeId,
                serviceId = serviceId,
                date = date.toString(),
            ).payload()
        }.map { raw -> BookingSlots.available(raw = raw, date = date, now = clock.instant()) }

    /**
     * Пустые id и прошедшее время в сеть не уходят: сервер ответил бы тем же
     * отказом, но платой были бы запрос и молчание экрана на время его
     * выполнения.
     *
     * Ответ без `id` отказом **не** считается — запись создана, а увидеть её
     * можно в «моих записях» (см. [toCreated]).
     */
    override suspend fun book(
        placeId: String,
        serviceId: String,
        date: LocalDate,
        time: LocalTime,
    ): ApiResult<Appointment> {
        val slotHasPassed = BookingSlots.startsAt(date, time).isBefore(clock.instant())
        if (placeId.isBlank() || serviceId.isBlank() || slotHasPassed) {
            return ApiResult.Failure(
                ApiError.Business(BookingRepository.INVALID_REQUEST_CODE),
            )
        }

        return apiCall {
            api.book(
                BookAppointmentRequest(
                    placeId = placeId,
                    serviceId = serviceId,
                    date = date.toString(),
                    startTime = time.toServerTime(),
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
