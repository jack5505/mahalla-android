package uz.mahalla.feature.services.domain

import java.time.Instant

/**
 * Что стало с заказом услуги (ответ `POST walkin/send`, схема `Response`
 * контроллера `walk-in`).
 *
 * Заявка не «отправлена и забыта»: мастер её принимает, отклоняет или
 * предлагает другое время, — поэтому экран после отправки показывает не
 * «готово», а состояние заявки.
 *
 * @param queuePosition место в живой очереди; `null` — сервер его не считает
 * (например, заявка ещё не принята).
 * @param counterTime встречное время от мастера (`HH:mm` строкой): разбирать
 * его в `LocalTime` незачем — оно только показывается.
 */
data class ServiceRequest(
    val id: String,
    val placeId: String? = null,
    val userName: String? = null,
    val serviceName: String? = null,
    val barberNote: String? = null,
    val status: ServiceRequestStatus = ServiceRequestStatus.Unknown,
    val queuePosition: Int? = null,
    val estimatedWaitMinutes: Int? = null,
    val counterTime: String? = null,
    val createdAt: Instant? = null,
)

/**
 * Состояния заявки из схемы стенда.
 *
 * [Unknown] — не «ошибка», а значение, которого ещё нет в приложении: новый
 * статус бэкенда должен быть виден как «заявка в работе», а не прятать её.
 */
enum class ServiceRequestStatus(private val serverValue: String) {
    /** Отправлена, мастер ещё не ответил. */
    Pending("PENDING"),
    Accepted("ACCEPTED"),
    Declined("DECLINED"),

    /** Мастер предложил другое время — оно приезжает в `counterTime`. */
    CounterOffered("COUNTER_OFFERED"),

    /** Принята, человек стоит в очереди. */
    Waiting("WAITING"),

    /** Услуга уже оказывается. */
    InChair("IN_CHAIR"),
    Completed("COMPLETED"),
    Cancelled("CANCELLED"),
    NoShow("NO_SHOW"),
    Expired("EXPIRED"),
    Unknown(""),
    ;

    /** Дальше состояние само не изменится — перечитывать заявку незачем. */
    val isFinal: Boolean
        get() = this == Completed || this == Cancelled ||
            this == Declined || this == NoShow || this == Expired

    /** Отказ: заявку придётся оформлять заново. */
    val isRejected: Boolean
        get() = this == Declined || this == NoShow || this == Expired

    companion object {
        fun fromServer(value: String?): ServiceRequestStatus {
            val raw = value?.trim().orEmpty()
            if (raw.isEmpty()) return Unknown
            return entries.firstOrNull { it.serverValue.equals(raw, ignoreCase = true) } ?: Unknown
        }
    }
}
