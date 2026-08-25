package uz.mahalla.core.format

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Форматирование дат и времени (эпик 1.5).
 *
 * Шаблоны фиксированы (`dd.MM.yyyy`, `HH:mm`) — так пишут и по-узбекски, и
 * по-русски, поэтому локаль на вывод не влияет и результат совпадает с
 * макетом на обоих языках. `java.time` доступен нативно с minSdk 26,
 * desugaring не нужен.
 */
object DateTimeFormatters {

    /** Ташкент — единственная зона Узбекистана. */
    val AppZone: ZoneId = ZoneId.of("Asia/Tashkent")

    private val datePattern = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.ROOT)
    private val timePattern = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val dateTimePattern = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", Locale.ROOT)

    fun date(instant: Instant, zone: ZoneId = AppZone): String =
        datePattern.format(instant.atZone(zone))

    fun time(instant: Instant, zone: ZoneId = AppZone): String =
        timePattern.format(instant.atZone(zone))

    fun dateTime(instant: Instant, zone: ZoneId = AppZone): String =
        dateTimePattern.format(instant.atZone(zone))

    /**
     * Ожидание в очереди: до часа — минуты (`12`), дальше — `ч:мм` (`1:05`).
     * Подпись единиц берётся из ресурсов, форматтер отдаёт только число.
     */
    fun waitingTime(totalMinutes: Long): String = when {
        totalMinutes < MINUTES_IN_HOUR -> totalMinutes.toString()
        else -> {
            val minutes = (totalMinutes % MINUTES_IN_HOUR).toString().padStart(2, '0')
            "${totalMinutes / MINUTES_IN_HOUR}:$minutes"
        }
    }

    private const val MINUTES_IN_HOUR = 60L
}
