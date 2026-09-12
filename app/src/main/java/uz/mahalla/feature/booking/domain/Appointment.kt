package uz.mahalla.feature.booking.domain

import uz.mahalla.core.format.DateTimeFormatters
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * Состояние записи (эпик #11, issue #97).
 *
 * Значения — перечисление бэкенда из схемы `AppointmentResponse` (снято со
 * стенда 2026-09-04): `PENDING`, `CONFIRMED`, `CANCELLED`, `COMPLETED`,
 * `NO_SHOW`. Оно короче, чем у очереди (issue #96): промежуточных «в кресле» и
 * «предложено другое время» у записи на время нет.
 *
 * [Unknown] обязателен: состояние меняет заведение через свою панель
 * (`PUT appointments/{id}/status`, эпик #16), и новое значение не должно
 * прятать запись из списка.
 */
enum class AppointmentStatus(val apiValue: String) {
    /** Запись создана, заведение ещё не подтвердило. */
    Pending("PENDING"),

    /** Заведение подтвердило: время за человеком. */
    Confirmed("CONFIRMED"),

    Cancelled("CANCELLED"),
    Completed("COMPLETED"),

    /** Не пришёл. */
    NoShow("NO_SHOW"),

    Unknown(""),
    ;

    companion object {
        fun fromApi(value: String?): AppointmentStatus {
            val normalized = value?.trim()?.uppercase(Locale.ROOT)?.replace('-', '_')
                ?: return Unknown
            if (normalized.isEmpty()) return Unknown
            return entries.firstOrNull { it.apiValue == normalized } ?: Unknown
        }
    }
}

/**
 * К кому запись: к мастеру (issue #97) или к врачу (issue #99).
 *
 * Модель записи на экране у обеих вертикалей одна, а на бэкенде — нет: у
 * каждой своя схема и свои ручки, и списка (`appointments/my` против
 * `hospitals/appointments/my`), и отмены (issue #167). Поэтому вертикаль — не
 * поле самой записи (сервер её не сообщает), а признак того, откуда список
 * пришёл: он выбирает источник и заголовок экрана.
 *
 * Незнакомое значение аргумента маршрута читается как [Barber] (см.
 * [byName]) — на экран без списка это не уводит.
 */
enum class AppointmentVertical {
    /** Мастера: `barber-services` + `appointments` (issue #97). */
    Barber,

    /** Больницы: `hospitals/appointments` (issue #99). */
    Doctor,
    ;

    companion object {
        fun byName(value: String?): AppointmentVertical =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: Barber
    }
}

/**
 * Запись на время.
 *
 * @param id идентификатор с сервера. **Может быть пустым**, и только в одном
 * случае — сразу после создания, если ответ `POST appointments` пришёл без
 * `id`. Это не отказ: запись создана, и увидеть её всё равно можно в «моих
 * записях» (`GET appointments/my`), в отличие от талона очереди, где читать
 * состояние нечем и ответ без `id` приходится считать негодным (issue #96).
 * В самом списке запись без `id` отбрасывается — отменить её нечем.
 * @param date и [startTime] — день и время в зоне заведения
 * ([DateTimeFormatters.AppZone]). Оба необязательны: запись без времени
 * показывается как есть, а не прячется.
 * @param priceSum цена услуги на момент записи; ноль — «не названа».
 * @param doctorId запись к врачу (issue #99): `doctorId` из
 * `HospitalAppointmentResponse`, `null` у записи к мастеру. Сервер не называет
 * врача в [serviceName] (схема этого поля вовсе не знает), поэтому имя
 * дотягивается отдельным запросом по этому id —
 * [uz.mahalla.feature.hospital.data.DefaultHospitalRepository] (issue #219).
 */
data class Appointment(
    val id: String,
    val placeId: String? = null,
    val serviceId: String? = null,
    val serviceName: String? = null,
    val doctorId: String? = null,
    val priceSum: Long = 0,
    val date: LocalDate? = null,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
    val status: AppointmentStatus = AppointmentStatus.Unknown,
    val createdAt: Instant? = null,
) {

    /** Момент начала; `null` — сервер не назвал дату или время. */
    fun startsAt(zone: ZoneId = DateTimeFormatters.AppZone): Instant? {
        val day = date ?: return null
        return BookingSlots.startsAt(day, startTime ?: LocalTime.MIDNIGHT, zone)
    }

    /**
     * Дальше состояние не изменится. [AppointmentStatus.Unknown] финальным
     * **не** считается: незнакомое значение — это «неизвестно, чем кончилось»,
     * и объявлять запись закрытой по нему нельзя (то же правило, что у талона
     * очереди).
     */
    val isFinal: Boolean
        get() = status == AppointmentStatus.Cancelled ||
            status == AppointmentStatus.Completed ||
            status == AppointmentStatus.NoShow

    /**
     * Отменить можно всё незакрытое, у чего есть чем отменять.
     *
     * Прошедшее время отмену не запрещает: `PENDING`, до которого заведение
     * так и не дошло, человек вправе снять, а последнее слово всё равно за
     * сервером — его отказ экран покажет текстом (issue #34).
     */
    val canCancel: Boolean get() = !isFinal && id.isNotBlank()

    /**
     * Перенести можно то, что ещё можно отменить и на что есть чем записаться
     * заново.
     *
     * Правило строже, чем у [canCancel], и это не перестраховка: **своей ручки
     * переноса у бэкенда нет** (проверено по `/v3/api-docs` 2026-09-08 — у
     * `appointments` есть только `my`, `{id}`, `{id}/cancel`, `{id}/status`),
     * поэтому перенос собирается из двух: новая запись плюс отмена старой. Для
     * новой нужны заведение и услуга, а сервер их в `AppointmentResponse`
     * называть не обязан — запись без них показывается, но перенести её нечем.
     *
     * Прошедшее время перенос не запрещает по той же причине, что и отмену:
     * `PENDING`, до которого заведение так и не дошло, человек вправе
     * передвинуть, а последнее слово всё равно за сервером.
     */
    val canReschedule: Boolean
        get() = canCancel && !placeId.isNullOrBlank() && !serviceId.isNullOrBlank()

    /**
     * Запись ещё предстоит. Время неизвестно — считаем, что предстоит: прятать
     * незакрытую запись в «прошедшие» значило бы спрятать и кнопку отмены.
     */
    fun isUpcoming(now: Instant, zone: ZoneId = DateTimeFormatters.AppZone): Boolean {
        if (isFinal) return false
        val starts = startsAt(zone) ?: return true
        return !starts.isBefore(now)
    }
}

/**
 * Итог переноса записи.
 *
 * Перенос — не одна операция, а две: сначала создаётся запись на новое время,
 * потом отменяется старая (порядок объяснён в
 * [uz.mahalla.feature.booking.data.BookingRepository.reschedule]). Вторая может
 * не удаться, когда первая уже прошла, — и это **не** отказ переноса: новое
 * время за человеком.
 *
 * @param appointment запись на новое время — та, которую вернул сервер.
 * @param previousCancelled удалось ли снять старую. `false` — у человека
 * осталось две записи, и экран обязан сказать об этом прямо: молча оставить
 * лишнюю значит подвести и человека, и заведение.
 */
data class Rescheduled(
    val appointment: Appointment,
    val previousCancelled: Boolean,
)

/**
 * Список записей, разложенный так, как его читают: сначала то, куда идти, потом
 * то, что было.
 *
 * Отдельной функцией, а не сортировкой в UI, потому что правило неочевидное и
 * его надо проверять тестом: «активные» — это не «не отменённые», а «ещё
 * предстоят» ([Appointment.isUpcoming]), и ближайшая запись обязана быть
 * первой, тогда как в прошедших первой идёт самая свежая.
 */
object AppointmentSections {

    data class Split(
        val upcoming: List<Appointment> = emptyList(),
        val past: List<Appointment> = emptyList(),
    ) {
        val isEmpty: Boolean get() = upcoming.isEmpty() && past.isEmpty()
    }

    fun split(
        appointments: List<Appointment>,
        now: Instant,
        zone: ZoneId = DateTimeFormatters.AppZone,
    ): Split {
        val (upcoming, past) = appointments.partition { it.isUpcoming(now, zone) }
        // Записи без времени — в конец обоих списков: они ни к какому дню не
        // привязаны, и наверху вытеснили бы то, куда человеку идти сегодня.
        // Для прошедших это `nullsFirst().reversed()`: разворот меняет местами
        // и `null`'ы, поэтому «сначала свежие, безвременные в конце» получается
        // именно так, а не разворотом `nullsLast()`.
        val byStart = compareBy(nullsLast<Instant>()) { appointment: Appointment ->
            appointment.startsAt(zone)
        }
        val byStartDescending = compareBy(nullsFirst<Instant>()) { appointment: Appointment ->
            appointment.startsAt(zone)
        }.reversed()
        return Split(
            upcoming = upcoming.sortedWith(byStart),
            past = past.sortedWith(byStartDescending),
        )
    }
}

/**
 * Страница «моих записей».
 *
 * @param hasMore есть ли что догружать. Считается по `last`, а при его
 * отсутствии — по `page`/`totalPages`; полного молчания сервера о страницах
 * достаточно, чтобы остановиться (то же правило, что у уведомлений, issue #81,
 * и у «моих заведений», issue #94): лучше не показать хвост списка, чем
 * зациклить догрузку одной и той же страницы.
 */
data class AppointmentPage(
    val items: List<Appointment> = emptyList(),
    val hasMore: Boolean = false,
)
