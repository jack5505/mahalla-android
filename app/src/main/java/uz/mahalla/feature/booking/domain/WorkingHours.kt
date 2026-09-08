package uz.mahalla.feature.booking.domain

import uz.mahalla.core.format.DateTimeFormatters
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Сетка времени, которую приложение предлагает выбрать, когда занятости
 * исполнителя сервер не сообщает.
 *
 * Такой случай у бэкенда не один: свободные слоты есть только у брони
 * (`barber-services/.../slots`, issue #97), а у больниц (issue #99) и у
 * мастеров-фрилансеров (issue #107) ручки занятости нет вовсе. Обе вертикали
 * поэтому строят сетку на клиенте и называют её «удобное время», а не
 * «свободное»: выдать её за проверенные слоты значило бы обещать от имени
 * сервера то, чего он не говорил. Заказ и запись после этого создаются со
 * статусом `PENDING` — время подтверждает исполнитель.
 *
 * Правило здесь ровно одно — **не предлагать прошедшее**, и живёт оно в одном
 * месте: две копии этой арифметики разошлись бы при первой же правке.
 *
 * Вся арифметика — в зоне заведения ([DateTimeFormatters.AppZone],
 * `Asia/Tashkent`): на телефоне с часами в другой зоне «сегодня» и «уже
 * прошло» считались бы неверно.
 */
object WorkingHours {

    /** Первый час, на который записывают. */
    val DEFAULT_OPENS_AT: LocalTime = LocalTime.of(8, 0)

    /** Последний. */
    val DEFAULT_LAST_START: LocalTime = LocalTime.of(19, 30)

    /** Шаг сетки. Полчаса — привычный шаг и в регистратуре, и у мастера. */
    const val DEFAULT_STEP_MINUTES = 30L

    /**
     * Время, которое можно предложить на выбранный день.
     *
     * @param date день сетки. Прошедший день целиком даёт пустой список; на
     * сегодня уходит всё, что уже наступило.
     */
    fun times(
        date: LocalDate,
        now: Instant,
        zone: ZoneId = DateTimeFormatters.AppZone,
        opensAt: LocalTime = DEFAULT_OPENS_AT,
        lastStart: LocalTime = DEFAULT_LAST_START,
        stepMinutes: Long = DEFAULT_STEP_MINUTES,
    ): List<LocalTime> {
        val today = BookingSlots.today(now, zone)
        if (date.isBefore(today)) return emptyList()

        val grid = generateSequence(opensAt.takeIf { it <= lastStart }) { previous ->
            val next = previous.plusMinutes(stepMinutes)
            // Сетка не переходит через полночь: `plusMinutes` заворачивается, и
            // без этой проверки последовательность стала бы бесконечной.
            next.takeIf { it > previous && it <= lastStart }
        }.toList()

        if (date.isAfter(today)) return grid

        val currentTime = now.atZone(zone).toLocalTime()
        return grid.filter { !it.isBefore(currentTime) }
    }
}
