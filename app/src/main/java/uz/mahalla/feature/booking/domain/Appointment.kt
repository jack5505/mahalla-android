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
 */
data class Appointment(
    val id: String,
    val placeId: String? = null,
    val serviceId: String? = null,
    val serviceName: String? = null,
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
