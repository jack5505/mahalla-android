package uz.mahalla.feature.cinema.domain

import androidx.compose.runtime.Immutable
import uz.mahalla.core.format.DateTimeFormatters
import uz.mahalla.feature.booking.domain.BookingSlots
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Сеанс кинотеатра (issue #106).
 *
 * Приезжает из `GET /api/v1/cinema/places/{placeId}/schedule?date=…` — схема
 * `CinemaSession`. Параметр `date` **обязателен**: без него бэкенд отвечает
 * `400 MISSING_PARAMETER` (проверено), то есть расписание всегда на один день.
 *
 * @param priceSum цена билета. Бэкенд отдаёт `ticketPrice` целым числом
 * **без** дробного близнеца (в кошельке пара `balance`/`balanceSom` есть —
 * issue #62, здесь нет), поэтому считаем сумами, как в «Еде» (issue #9), в
 * брони (issue #97) и у врачей (issue #99).
 * @param availableSeats сколько мест осталось. `null` — «сервер не сказал», и
 * это **не** «мест нет»: билет в таком случае предлагается, а последнее слово
 * остаётся за сервером. Схемы зала контракт не отдаёт вовсе (см.
 * [SeatChoice]), поэтому число свободных мест — единственное, что о зале
 * известно.
 * @param isActive отменённый сеанс не продаётся. `null` читается как «идёт»,
 * по той же причине, что и у фильма.
 */
@Immutable
data class CinemaSession(
    val id: String,
    val movieId: String? = null,
    val placeId: String? = null,
    val hallName: String? = null,
    val date: LocalDate? = null,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
    val priceSum: Long = 0,
    val totalSeats: Int? = null,
    val availableSeats: Int? = null,
    val isActive: Boolean = true,
) {

    /** Момент начала; `null` — сервер не назвал дату или время. */
    fun startsAt(zone: ZoneId = DateTimeFormatters.AppZone): Instant? {
        val day = date ?: return null
        return BookingSlots.startsAt(day, startTime ?: LocalTime.MIDNIGHT, zone)
    }

    /** Мест не осталось — и сервер об этом прямо сказал. */
    val isSoldOut: Boolean get() = availableSeats != null && availableSeats <= 0

    /**
     * Сеанс уже начался. Время неизвестно — считаем, что нет: прятать сеанс
     * из-за неприехавшего поля значило бы прятать и кнопку покупки.
     */
    fun hasStarted(
        now: Instant,
        zone: ZoneId = DateTimeFormatters.AppZone,
    ): Boolean = startsAt(zone)?.isBefore(now) == true

    /**
     * Билет можно предлагать. Три причины отказать — и каждая объясняется
     * экраном словами: сеанс отменён, мест нет, сеанс начался. Кнопка, которая
     * молча ничего не делает, читается как сломанная.
     */
    fun isBookable(
        now: Instant,
        zone: ZoneId = DateTimeFormatters.AppZone,
    ): Boolean = id.isNotBlank() && isActive && !isSoldOut && !hasStarted(now, zone)
}

/**
 * Правила расписания: какие сеансы вообще показывать и на какие дни.
 *
 * Расписание считает сервер — приложение добавляет к его ответу ровно одно
 * правило, **не предлагать прошедшее**: в списке на сегодня сервер отдаёт и
 * утренние сеансы, купить билет на которые уже нельзя. То же правило, что у
 * слотов брони ([BookingSlots.available], issue #97).
 *
 * Календарь дней берётся оттуда же: день выбирают одинаково во всех
 * вертикалях, и вторая копия правила разъехалась бы с первой.
 */
object CinemaSchedule {

    /** Дни, среди которых выбирают дату сеанса. */
    fun dates(
        now: Instant,
        zone: ZoneId = DateTimeFormatters.AppZone,
    ): List<LocalDate> = BookingSlots.dates(now = now, zone = zone)

    /**
     * Сеансы, которые можно показать: без прошедших, по возрастанию времени.
     *
     * Отменённые (`isActive = false`) не показываются вовсе — это не «нельзя
     * купить», а «сеанса не будет».
     *
     * @param movieId оставить только сеансы этого фильма. Сеанс, у которого
     * сервер не назвал `movieId`, при фильтре выпадает: приписать его фильму
     * не на чем, а показать чужой сеанс на карточке фильма хуже, чем не
     * показать свой.
     */
    fun upcoming(
        sessions: List<CinemaSession>,
        now: Instant,
        movieId: String? = null,
        zone: ZoneId = DateTimeFormatters.AppZone,
    ): List<CinemaSession> = sessions
        .filter { session ->
            session.isActive &&
                !session.hasStarted(now, zone) &&
                (movieId == null || session.movieId == movieId)
        }
        .sortedWith(compareBy(nullsLast()) { session -> session.startsAt(zone) })
}

/**
 * Место в зале — то, что человек указывает при покупке.
 *
 * **Схемы зала у бэкенда нет.** Тело `POST cinema/sessions/{sessionId}/buy` в
 * `/v3/api-docs` описано как `object` с `additionalProperties: string`, то
 * есть поля не названы вовсе; из всей модели зала контракт знает только
 * `totalSeats` и `availableSeats` у сеанса, а списка занятых мест не отдаёт
 * **никак** (проверено по полной схеме стенда 2026-09-04: слов `seat`/`hall`
 * в ней ровно столько). Значит нарисовать зал приложению нечем — оно не
 * узнает, какие места свободны.
 *
 * Отсюда решение: место — **необязательное** текстовое поле, а экран честно
 * пишет, что его подтверждает кинотеатр. Имя ключа (`seatNumber`) взято из
 * ответа того же эндпоинта (`CinemaTicket.seatNumber` — единственное поле
 * билета, которое задаёт покупатель); тем же способом выведены тела запросов
 * в issue #76, #84 и #97.
 *
 * @param seatNumber то, что набрал человек. Пробелы местом не считаются — ни
 * длиной, ни содержанием.
 */
@Immutable
data class SeatChoice(val seatNumber: String = "") {

    val trimmed: String get() = seatNumber.trim()

    /**
     * Ограничения на длину в контракте нет — это защита от вставленного в
     * поле романа, а не правило бэкенда. Лишнее не режется на вводе: человек
     * не поймёт, куда пропали символы (то же правило, что у жалобы врачу в
     * issue #99).
     */
    val isTooLong: Boolean get() = trimmed.length > MAX_LENGTH

    val canSubmit: Boolean get() = !isTooLong

    /** Пустое место уходит **отсутствующим** полем, а не пустой строкой. */
    fun seatOrNull(): String? = trimmed.takeIf(String::isNotBlank)

    companion object {
        const val MAX_LENGTH = 20
    }
}
