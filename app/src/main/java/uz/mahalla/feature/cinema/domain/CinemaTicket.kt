package uz.mahalla.feature.cinema.domain

import androidx.compose.runtime.Immutable
import java.time.Instant
import java.util.Locale

/**
 * Состояние билета (issue #106).
 *
 * Значения — перечисление бэкенда из схемы `CinemaTicket` (снято со стенда
 * 2026-09-04): `ACTIVE`, `USED`, `CANCELLED`, `REFUNDED`.
 *
 * [Unknown] обязателен, как и во всех остальных вертикалях: состояние меняет
 * кинотеатр своей панелью (эпик #16), и новое значение не должно ни прятать
 * билет из списка, ни объявлять его закрытым.
 */
enum class CinemaTicketStatus(val apiValue: String) {
    /** Куплен и действителен. */
    Active("ACTIVE"),

    /** Предъявлен на входе. */
    Used("USED"),

    Cancelled("CANCELLED"),

    /** Возвращён и деньги отданы. */
    Refunded("REFUNDED"),

    Unknown(""),
    ;

    companion object {
        fun fromApi(value: String?): CinemaTicketStatus {
            val normalized = value?.trim()?.uppercase(Locale.ROOT)?.replace('-', '_')
                ?: return Unknown
            if (normalized.isEmpty()) return Unknown
            return entries.firstOrNull { it.apiValue == normalized } ?: Unknown
        }
    }
}

/**
 * Купленный билет.
 *
 * @param code код билета (`qrCode` в схеме). Показывается моноширинными
 * цифрами (`tnum`, как требует ТЗ) — его сверяет глазами контролёр, и цифры
 * не должны «плясать». [uz.mahalla.core.format.TicketFormatter] к нему
 * **не** применяется: тот собирает талон вида `A-042` из сектора и номера, а
 * их у билета нет — сектор пришлось бы выдумать, и человек назвал бы
 * кинотеатру номер, которого тот не знает (то же правило, что у талона
 * очереди в issue #96).
 * @param seatNumber место. Может быть пустым: место в запросе необязательно
 * (см. [SeatChoice]), и назначить его вправе сам кинотеатр.
 * @param sessionId сеанс, на который куплен билет. Ни фильма, ни его времени
 * в `CinemaTicket` нет — экран «мои билеты» показывает то, что приехало.
 */
@Immutable
data class CinemaTicket(
    val id: String,
    val sessionId: String? = null,
    val seatNumber: String? = null,
    val priceSum: Long = 0,
    val code: String? = null,
    val status: CinemaTicketStatus = CinemaTicketStatus.Unknown,
    val createdAt: Instant? = null,
) {

    /**
     * Дальше состояние не изменится. [CinemaTicketStatus.Unknown] финальным
     * **не** считается: «неизвестно, чем кончилось» — не «кончилось», а
     * объявив билет закрытым, экран заодно спрятал бы кнопку возврата.
     */
    val isFinal: Boolean
        get() = status == CinemaTicketStatus.Used ||
            status == CinemaTicketStatus.Cancelled ||
            status == CinemaTicketStatus.Refunded

    /**
     * Вернуть можно всё незакрытое, у чего есть чем возвращать.
     *
     * Начавшийся сеанс возврату не помеха — да и определить это нечем: даты
     * сеанса в билете нет. Правила возврата знает кинотеатр, и его отказ
     * экран покажет текстом (issue #34).
     */
    val canCancel: Boolean get() = !isFinal && id.isNotBlank()
}

/**
 * Страница «моих билетов».
 *
 * @param hasMore есть ли что догружать. Считается по `last`, а при его
 * отсутствии — по `page`/`totalPages`; полного молчания сервера о страницах
 * достаточно, чтобы остановиться (то же правило, что у уведомлений, issue
 * #81, и у записей, issue #97): лучше не показать хвост списка, чем зациклить
 * догрузку одной и той же страницы.
 */
data class CinemaTicketPage(
    val items: List<CinemaTicket> = emptyList(),
    val hasMore: Boolean = false,
)

/**
 * Порядок билетов в списке: сначала действующие, внутри — свежие сверху.
 *
 * Отдельной функцией, а не сортировкой в UI, потому что правило неочевидное и
 * его надо проверять тестом. Разделить билеты на «предстоящие» и «прошедшие»,
 * как записи (issue #97), **нельзя**: времени сеанса в билете нет вовсе — есть
 * только `sessionId`, — поэтому единственное, чем экран располагает, это
 * статус и момент покупки.
 */
object CinemaTickets {

    fun ordered(tickets: List<CinemaTicket>): List<CinemaTicket> = tickets.sortedWith(
        compareBy<CinemaTicket> { it.isFinal }
            .thenByDescending { ticket -> ticket.createdAt ?: Instant.EPOCH },
    )
}
