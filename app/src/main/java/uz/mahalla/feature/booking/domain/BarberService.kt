package uz.mahalla.feature.booking.domain

import uz.mahalla.core.format.DateTimeFormatters
import uz.mahalla.core.format.parseServerLocalTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Услуга заведения, на которую записываются (эпик #11, issue #97).
 *
 * Приезжает из `GET /api/v1/barber-services/places/{placeId}` — схема
 * `ServiceResponse`. Имя в `/v3/api-docs` встречается один раз, коллизии
 * springdoc здесь нет, поэтому поля взяты из схемы как есть.
 *
 * @param priceSum цена. Бэкенд отдаёт `priceAmount` целым числом **без**
 * дробного близнеца (в кошельке пара `balance`/`balanceSom` есть — issue #62,
 * здесь нет), поэтому считаем сумами, как в «Еде» (issue #9). Ноль — «цена не
 * названа»: экран тогда её просто не показывает, а не пишет «0 сум».
 * @param durationMinutes сколько занимает услуга. Нужна не для расчёта слотов
 * (их считает сервер), а чтобы человек понимал, на сколько записывается.
 * @param isActive выключенную услугу заведение временно не оказывает — она в
 * списке не предлагается.
 */
data class BarberService(
    val id: String,
    val title: String,
    val description: String? = null,
    val priceSum: Long = 0,
    val durationMinutes: Int? = null,
    val isActive: Boolean = true,
)

/**
 * Свободные слоты и календарь — правила, по которым экран решает, что можно
 * предложить.
 *
 * **Слоты считает сервер, а не приложение**: занятость знает только он, и
 * вычислять сетку на клиенте значило бы предлагать уже занятое время. Клиент
 * добавляет к его ответу ровно одно правило — прошедший слот не предлагать
 * (см. [available]): в списке на сегодня сервер вполне может отдать время,
 * которое наступило, пока человек выбирал услугу.
 *
 * Вся арифметика — в зоне заведения ([DateTimeFormatters.AppZone],
 * `Asia/Tashkent`): для устройства с часами в другой зоне «сегодня» и «уже
 * прошло» иначе считались бы неверно.
 */
object BookingSlots {

    /** На сколько дней вперёд предлагается календарь, включая сегодня. */
    const val CALENDAR_DAYS = 14

    /** Дни, среди которых выбирают дату записи. */
    fun dates(
        now: Instant,
        zone: ZoneId = DateTimeFormatters.AppZone,
        days: Int = CALENDAR_DAYS,
    ): List<LocalDate> {
        val today = today(now, zone)
        return (0 until days.coerceAtLeast(1)).map { day -> today.plusDays(day.toLong()) }
    }

    fun today(now: Instant, zone: ZoneId = DateTimeFormatters.AppZone): LocalDate =
        now.atZone(zone).toLocalDate()

    /**
     * Что из ответа сервера можно предложить.
     *
     * Порядок правил: разобрать → выбросить прошедшее → упорядочить. Дубликаты
     * снимаются, потому что список рисуется с ключом по времени, а сервер
     * вполне может прислать `"10:00"` и `"10:00:00"` — для человека это одно и
     * то же время, а для `LazyColumn` — два одинаковых ключа.
     *
     * Неразобранная строка просто выпадает: из-за одного мусорного значения
     * прятать остальные слоты незачем.
     *
     * @param date день, на который сервер отдал слоты. Прошедший день целиком
     * даёт пустой список — даже если сервер что-то в нём предложил.
     */
    fun available(
        raw: List<String>,
        date: LocalDate,
        now: Instant,
        zone: ZoneId = DateTimeFormatters.AppZone,
    ): List<LocalTime> {
        val today = today(now, zone)
        if (date.isBefore(today)) return emptyList()

        val parsed = raw.mapNotNull(::parseServerLocalTime).distinct().sorted()
        if (date.isAfter(today)) return parsed

        val currentTime = now.atZone(zone).toLocalTime()
        return parsed.filter { !it.isBefore(currentTime) }
    }

    /**
     * Момент начала слота. Нужен экрану подтверждения и списку записей: дата и
     * время порознь не отвечают на вопрос «это уже прошло?».
     */
    fun startsAt(
        date: LocalDate,
        time: LocalTime,
        zone: ZoneId = DateTimeFormatters.AppZone,
    ): Instant = date.atTime(time).atZone(zone).toInstant()
}
