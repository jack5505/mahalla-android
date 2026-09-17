package uz.mahalla.feature.hospital.domain

import androidx.compose.runtime.Immutable
import uz.mahalla.core.format.DateTimeFormatters
import uz.mahalla.core.format.parseServerLocalTime
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
 * @param consultationPriceSum цена приёма в **сумах**. Бэкенд отдаёт
 * `consultationPrice` в тийинах, пересчёт делает маппер (`Money.tiyinToSom`,
 * issue #149). Ноль — «цена не названа»: экран тогда её просто не показывает,
 * а не пишет «0 сум».
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
 * Свободный слот записи к врачу (issue #181).
 *
 * Приезжает из `GET /api/v1/hospitals/doctors/{id}/slots?date=` —
 * `ApiResponseListString`, тот же вид ответа, что у слотов брони (issue #97).
 *
 * @param raw строка ровно в том виде, в котором её отдал сервер
 * (`"09:00"`/`"09:00:00"`). Именно она уходит в `startTime` записи —
 * без повторного разбора и сборки: строка слота не переводится между зонами
 * нигде на этом пути, но лишний шаг «разобрали → собрали заново» уже один раз
 * стоил вертикали расхождения в пять часов между UTC и Asia/Tashkent
 * (issue #144), и здесь его нет вовсе.
 * @param time тот же момент, разобранный в [LocalTime] — только для сортировки,
 * отсечения прошедшего времени и показа на экране.
 */
@Immutable
data class DoctorSlot(
    val raw: String,
    val time: LocalTime,
)

/**
 * Свободные слоты и календарь — правила, по которым экран решает, что можно
 * предложить.
 *
 * **Слоты считает сервер, а не приложение** (issue #181: до неё ручки слотов
 * у больниц не было вовсе, и сетку времени строил клиент). Правило то же, что
 * у брони ([BookingSlots.available]): из ответа сервера убирается только уже
 * наступившее время сегодняшнего дня.
 *
 * Вся арифметика — в зоне заведения ([DateTimeFormatters.AppZone],
 * `Asia/Tashkent`): на телефоне с часами в другой зоне «сегодня» и «уже
 * прошло» считались бы неверно.
 *
 * Календарь дней берётся у [BookingSlots] — день выбирают одинаково всюду.
 */
object DoctorSlots {

    /** Дни, среди которых выбирают дату записи. */
    fun dates(
        now: Instant,
        zone: ZoneId = DateTimeFormatters.AppZone,
    ): List<LocalDate> = BookingSlots.dates(now = now, zone = zone)

    /**
     * Что из ответа сервера можно предложить.
     *
     * Порядок правил: разобрать → выбросить прошедшее → упорядочить.
     * Неразобранная строка просто выпадает: из-за одного мусорного значения
     * прятать остальные слоты незачем. Дубликаты по разобранному времени
     * снимаются — сервер вполне может прислать `"10:00"` и `"10:00:00"`, а для
     * `LazyColumn` это два одинаковых ключа.
     *
     * @param date день, на который сервер отдал слоты. Прошедший день целиком
     * даёт пустой список — даже если сервер что-то в нём предложил.
     */
    fun available(
        raw: List<String>,
        date: LocalDate,
        now: Instant,
        zone: ZoneId = DateTimeFormatters.AppZone,
    ): List<DoctorSlot> {
        val today = BookingSlots.today(now, zone)
        if (date.isBefore(today)) return emptyList()

        val parsed = raw
            .mapNotNull { value -> parseServerLocalTime(value)?.let { DoctorSlot(value, it) } }
            .distinctBy(DoctorSlot::time)
            .sortedBy(DoctorSlot::time)

        if (date.isAfter(today)) return parsed

        val currentTime = now.atZone(zone).toLocalTime()
        return parsed.filter { !it.time.isBefore(currentTime) }
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
 * (`HospitalBookRequest.complaint`, обязательны только `doctorId`, `date`,
 * `startTime`). Записаться, не объясняя причины, — нормальный случай.
 * @param slot выбранный слот целиком, а не разобранное время: `startTime`
 * записи уходит строкой [DoctorSlot.raw], ровно как её отдал сервер
 * (issue #181).
 */
@Immutable
data class DoctorAppointmentDraft(
    val doctorId: String? = null,
    val date: LocalDate? = null,
    val slot: DoctorSlot? = null,
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
            slot != null &&
            !isComplaintTooLong

    /** Пустая жалоба уходит отсутствующим полем, а не пустой строкой. */
    fun complaintOrNull(): String? = trimmedComplaint.takeIf(String::isNotBlank)

    companion object {
        const val MAX_COMPLAINT_LENGTH = 1000
    }
}
