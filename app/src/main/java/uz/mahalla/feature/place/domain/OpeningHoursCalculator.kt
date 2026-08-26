package uz.mahalla.feature.place.domain

import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * «Открыто сейчас» по расписанию (эпик 4.4).
 *
 * Флаг `isOpenNow` из выдачи считает сервер, но карточка живёт на экране
 * минутами и открывается из кэша, поэтому статус пересчитывается локально по
 * часам работы — иначе место остаётся «открытым» после закрытия.
 *
 * Смена через полночь (`18:00–02:00`) разбирается явно: в такое заведение
 * попадают ровно в тот час, когда наивное сравнение `now in opens..closes`
 * даёт «закрыто».
 */
object OpeningHoursCalculator {

    fun forDay(hours: List<OpeningHours>, day: DayOfWeek): OpeningHours? =
        hours.firstOrNull { it.dayOfWeek == day }

    /**
     * `null` — расписания нет, статус неизвестен. Возвращать `false` в этом
     * случае нельзя: «закрыто» и «мы не знаем» — разные сообщения.
     */
    fun isOpenAt(hours: List<OpeningHours>, moment: LocalDateTime): Boolean? {
        if (hours.isEmpty()) return null
        val today = forDay(hours, moment.dayOfWeek)
        val yesterday = forDay(hours, moment.dayOfWeek.minus(1))
        if (today == null && yesterday == null) return null

        val time = moment.toLocalTime()

        // Вчерашняя ночная смена ещё может длиться: в 01:00 работает интервал,
        // открывшийся вчера в 18:00.
        if (yesterday != null && yesterday.isOvernight && time < yesterday.closesAt!!) return true

        if (today == null || today.isDayOff) return false
        if (today.isAroundTheClock) return true
        return if (today.isOvernight) {
            time >= today.opensAt!!
        } else {
            time >= today.opensAt!! && time < today.closesAt!!
        }
    }

    /**
     * Расписание в порядке недели, начиная с понедельника, с дырами вместо
     * отсутствующих дней. Экран рисует семь строк всегда — «дня нет в ответе»
     * и «выходной» для пользователя одно и то же.
     */
    fun weekSchedule(hours: List<OpeningHours>): List<OpeningHours> = DayOfWeek.entries.map { day ->
        forDay(hours, day) ?: OpeningHours(day, opensAt = null, closesAt = null)
    }
}
