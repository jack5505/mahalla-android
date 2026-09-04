package uz.mahalla.feature.hospital.domain

import androidx.compose.runtime.Immutable
import uz.mahalla.core.format.DateTimeFormatters
import uz.mahalla.feature.booking.domain.BookingSlots
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Врач больницы (эпик #11, issue #99).
 *
 * Приезжает из `GET /api/v1/hospitals/places/{placeId}/doctors` — схема
 * `DoctorResponse`. Имя в `/v3/api-docs` встречается один раз, коллизии
 * springdoc здесь нет, поэтому поля прочитаны как есть:
 * `id, name, specialty, bio, consultationPrice`.
 *
 * @param specialty специальность. Именно её человек и ищет («терапевт»,
 * «стоматолог»), поэтому она показывается рядом с именем, а не прячется в
 * описание.
 * @param consultationPriceSum цена приёма. Бэкенд отдаёт `consultationPrice`
 * целым числом **без** дробного близнеца (в кошельке пара
 * `balance`/`balanceSom` есть — issue #62, здесь нет), поэтому считаем сумами,
 * как в «Еде» (issue #9) и в брони (issue #97). Ноль — «цена не названа»:
 * экран тогда её просто не показывает, а не пишет «0 сум».
 */
@Immutable
data class Doctor(
    val id: String,
    val name: String,
    val specialty: String? = null,
    val bio: String? = null,
    val consultationPriceSum: Long = 0,
)

/**
 * Время приёма, которое приложение предлагает выбрать.
 *
 * **У больниц нет ручки свободных слотов.** В `hospital-controller` их четыре
 * (`doctors`, `addDoctor`, `appointments`, `appointments/my`), а
 * `barber-services/places/{placeId}/slots` из брони (issue #97) принимает
 * `serviceId` барберской услуги и к врачам отношения не имеет — проверено по
 * полной схеме стенда 2026-09-04. То есть занятость врача сервер клиенту не
 * сообщает **никак**.
 *
 * Отсюда решение: сетка времени строится на клиенте, и приложение честно
 * называет её «удобное время», а не «свободное». Разница видна и в том, что
 * происходит дальше: запись создаётся со статусом `PENDING`, и подтверждает её
 * больница. Выдать эту сетку за свободные слоты значило бы обещать от имени
 * сервера то, чего он не говорил.
 *
 * Вся арифметика — в зоне заведения ([DateTimeFormatters.AppZone],
 * `Asia/Tashkent`): на телефоне с часами в другой зоне «сегодня» и «уже
 * прошло» считались бы неверно.
 *
 * Календарь дней берётся у [BookingSlots]: день выбирают одинаково в обеих
 * вертикалях записи, и вторая копия правила разъехалась бы с первой.
 */
object DoctorSchedule {

    /** Первый приём. */
    val OPENS_AT: LocalTime = LocalTime.of(8, 0)

    /** Последний приём, на который записывают. */
    val LAST_START: LocalTime = LocalTime.of(19, 30)

    /** Шаг сетки. Полчаса — обычная длина приёма и привычный шаг в регистратуре. */
    const val STEP_MINUTES = 30L

    /** Дни, среди которых выбирают дату записи. */
    fun dates(
        now: Instant,
        zone: ZoneId = DateTimeFormatters.AppZone,
    ): List<LocalDate> = BookingSlots.dates(now = now, zone = zone)

    /**
     * Время, которое можно предложить на выбранный день.
     *
     * Единственное правило, которое здесь есть, — **не предлагать прошедшее**:
     * на сегодня из сетки уходит всё, что уже наступило, а прошедший день
     * целиком даёт пустой список. Занятость врача в этом не участвует, потому
     * что о ней приложению никто не сообщает (см. KDoc объекта).
     */
    fun times(
        date: LocalDate,
        now: Instant,
        zone: ZoneId = DateTimeFormatters.AppZone,
    ): List<LocalTime> {
        val today = BookingSlots.today(now, zone)
        if (date.isBefore(today)) return emptyList()

        val grid = generateSequence(OPENS_AT) { previous ->
            val next = previous.plusMinutes(STEP_MINUTES)
            // Сетка не переходит через полночь: `plusMinutes` заворачивается, и
            // без этой проверки последовательность стала бы бесконечной.
            next.takeIf { it > previous && it <= LAST_START }
        }.toList()

        if (date.isAfter(today)) return grid

        val currentTime = now.atZone(zone).toLocalTime()
        return grid.filter { !it.isBefore(currentTime) }
    }
}

/**
 * Черновик записи к врачу: что человек выбрал до отправки.
 *
 * Правилами это отдельный класс, а не поля состояния экрана, по той же
 * причине, что и [uz.mahalla.feature.place.domain.ReviewDraft] (issue #76):
 * форму нельзя проверить ни скриншотом, ни запросом, а «кнопка включилась
 * раньше времени» стоит человеку отказа сервера вместо подсказки на экране.
 *
 * @param complaint жалоба — **необязательное** поле контракта
 * (`BookRequest.complaint`, обязательны только `doctorId`, `date`,
 * `startTime`). Записаться, не объясняя причины, — нормальный случай.
 */
@Immutable
data class DoctorAppointmentDraft(
    val doctorId: String? = null,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val complaint: String = "",
) {

    /** Пробелы по краям жалобой не считаем — ни длиной, ни содержанием. */
    val trimmedComplaint: String get() = complaint.trim()

    /**
     * Ограничение бэкенда — `@Size(max = 1000)`. Резать текст на вводе нельзя:
     * человек не поймёт, куда пропали набранные символы, — поэтому лишнее
     * показывается ошибкой, а отправка блокируется.
     */
    val isComplaintTooLong: Boolean get() = trimmedComplaint.length > MAX_COMPLAINT_LENGTH

    val canSubmit: Boolean
        get() = !doctorId.isNullOrBlank() &&
            date != null &&
            time != null &&
            !isComplaintTooLong

    /** Пустая жалоба уходит отсутствующим полем, а не пустой строкой. */
    fun complaintOrNull(): String? = trimmedComplaint.takeIf(String::isNotBlank)

    companion object {
        const val MAX_COMPLAINT_LENGTH = 1000
    }
}
