package uz.mahalla.feature.queue.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.util.Locale

/**
 * Состояние талона электронной очереди (эпик #10, issue #96).
 *
 * Значения — перечисление бэкенда из схемы `Response` walk-in-контроллера
 * (снято со стенда 2026-09-04): `PENDING`, `ACCEPTED`, `DECLINED`,
 * `COUNTER_OFFERED`, `WAITING`, `IN_CHAIR`, `COMPLETED`, `CANCELLED`,
 * `NO_SHOW`, `EXPIRED`.
 *
 * [Unknown] обязателен: набор состояний задаёт заведение через свою панель
 * (`accept`/`decline`/`start`/`complete`), и новое значение не должно ронять
 * экран талона — он покажет его как «в работе».
 */
enum class WalkInStatus(val apiValue: String) {
    /** Запрос ушёл, мастер ещё не ответил. */
    Pending("PENDING"),

    /** Мастер принял запись. */
    Accepted("ACCEPTED"),

    /** Мастер отказал. */
    Declined("DECLINED"),

    /** Мастер предложил другое время ([WalkInTicket.counterTime]). */
    CounterOffered("COUNTER_OFFERED"),

    /** Человек в очереди и ждёт. */
    Waiting("WAITING"),

    /** Мастер начал обслуживание. */
    InChair("IN_CHAIR"),

    Completed("COMPLETED"),
    Cancelled("CANCELLED"),

    /** Не пришёл: место в очереди отдали дальше. */
    NoShow("NO_SHOW"),

    /** Запрос истёк, не дождавшись ответа мастера. */
    Expired("EXPIRED"),

    Unknown(""),
    ;

    companion object {
        fun fromApi(value: String?): WalkInStatus {
            val normalized = value?.trim()?.uppercase(Locale.ROOT)?.replace('-', '_')
                ?: return Unknown
            if (normalized.isEmpty()) return Unknown
            return entries.firstOrNull { it.apiValue == normalized } ?: Unknown
        }
    }
}

/**
 * Талон очереди.
 *
 * Приезжает **только** в ответе на `POST walkin/send` и `POST
 * walkin/{id}/cancel`: ручки чтения своего талона у бэкенда нет вовсе (ни
 * `GET walkin/my`, ни `GET walkin/{id}` — см. отчёт в issue #96). Поэтому у
 * талона есть [receivedAt]: экран обязан подписывать, **на какой момент**
 * известны позиция и время ожидания, — иначе номер в очереди, замерший в
 * момент записи, выглядел бы как живой.
 *
 * @param placeName название заведения. Приходит не с сервера (в схеме
 * `Response` его нет), а из карточки места, откуда талон и берут.
 * @param queuePosition место в очереди на [receivedAt]; `null` — сервер не
 * прислал (у `PENDING` его ещё нет: мастер не подтвердил запись).
 * @param counterTime время, которое предложил мастер вместо запрошенного
 * (`COUNTER_OFFERED`).
 * @param note комментарий мастера (`barberNote`).
 */
data class WalkInTicket(
    val id: String,
    val placeId: String,
    val placeName: String = "",
    val userName: String = "",
    val serviceName: String? = null,
    val status: WalkInStatus,
    val queuePosition: Int? = null,
    val estimatedWaitMinutes: Int? = null,
    val counterTime: LocalTime? = null,
    val note: String? = null,
    val createdAt: Instant? = null,
    val receivedAt: Instant,
) {

    /**
     * Показывать ли позицию и время ожидания.
     *
     * Числа живут ровно [QUEUE_INFO_TTL]: очередь двигают отмены и отказы
     * других людей, а перечитать её нечем — ручки чтения талона у бэкенда нет.
     * Показать позицию получасовой давности как текущую значит соврать про
     * главное, за чем на этот экран приходят; статус при этом остаётся видимым
     * вместе с временем, на которое он известен.
     */
    fun showsQueueInfo(now: Instant): Boolean =
        (queuePosition != null || estimatedWaitMinutes != null) &&
            !Duration.between(receivedAt, now).isNegative &&
            Duration.between(receivedAt, now) < QUEUE_INFO_TTL

    /** Талон старше [ACTIVE_TTL] — за живой не считается. */
    fun isOutdated(now: Instant): Boolean =
        Duration.between(receivedAt, now) >= ACTIVE_TTL

    companion object {
        /**
         * Две минуты. Дольше держать число на экране нельзя: оно перестаёт
         * быть правдой уже от первой чужой отмены.
         */
        val QUEUE_INFO_TTL: Duration = Duration.ofMinutes(2)

        /**
         * Двенадцать часов: очередь в парикмахерскую не переживает ночь, а
         * талон, о котором приложение не может ничего узнать, к утру — мусор.
         */
        val ACTIVE_TTL: Duration = Duration.ofHours(12)
    }
}

/**
 * Переходы состояний талона (issue #96) — чистыми функциями, как
 * `OrderStatusFlow` в «Еде»: экран рисует цепочку, а решает домен.
 */
object WalkInStatusFlow {

    /**
     * Путь удачной записи. Отказ, отмена, неявка и истечение в цепочку не
     * входят: они из неё выпадают, и подсвеченный «ждёте» под надписью
     * «мастер отказал» — прямое противоречие.
     *
     * `COUNTER_OFFERED` тоже вне цепочки: это не шаг вперёд, а вопрос
     * человеку — согласен ли он на другое время.
     */
    private val STAGES = listOf(
        WalkInStatus.Pending,
        WalkInStatus.Accepted,
        WalkInStatus.Waiting,
        WalkInStatus.InChair,
        WalkInStatus.Completed,
    )

    fun stages(): List<WalkInStatus> = STAGES

    /**
     * Дальше состояние не изменится. [WalkInStatus.Unknown] финальным **не**
     * считается: незнакомое значение — это «неизвестно, чем кончилось», и
     * объявлять талон закрытым по нему нельзя.
     */
    fun isFinal(status: WalkInStatus): Boolean = status == WalkInStatus.Completed ||
        status == WalkInStatus.Cancelled ||
        status == WalkInStatus.Declined ||
        status == WalkInStatus.NoShow ||
        status == WalkInStatus.Expired

    /** Талон ещё в игре: его показывают и его можно отменить. */
    fun isActive(status: WalkInStatus): Boolean = !isFinal(status)

    /**
     * Отменить можно, пока мастер не начал стрижку: после `IN_CHAIR` отмена —
     * это разговор с мастером, а не кнопка в приложении (то же правило, что у
     * заказа после начала готовки).
     *
     * [WalkInStatus.Unknown] отменить **разрешено**: незнакомое состояние не
     * должно запирать человека в очереди, а последнее слово всё равно за
     * сервером — его отказ экран покажет текстом (issue #34).
     */
    fun canCancel(status: WalkInStatus): Boolean =
        isActive(status) && status != WalkInStatus.InChair

    /** Номер текущего этапа; `-1` — состояния нет в цепочке. */
    fun stageIndex(status: WalkInStatus): Int = STAGES.indexOf(status)

    /** Этап пройден — его отмечают галочкой, а не точкой. */
    fun isStageDone(stage: WalkInStatus, status: WalkInStatus): Boolean {
        val current = stageIndex(status)
        if (current < 0) return false
        return STAGES.indexOf(stage) < current
    }

    /** Рисовать ли цепочку этапов вообще. */
    fun showsStages(status: WalkInStatus): Boolean = stageIndex(status) >= 0
}

/**
 * Запрос на запись (`SendRequest`): `placeId` и `userName` обязательны,
 * `serviceName` — нет.
 *
 * Поля хранятся так, как их набрал человек: молча укоротить имя значит не
 * объяснить, куда делись символы. Границы проверяет [WalkInRequestValidator],
 * обрезает — только [trimmed] перед отправкой.
 */
data class WalkInRequest(
    val placeId: String,
    val userName: String = "",
    val serviceName: String = "",
) {

    fun trimmed(): WalkInRequest = copy(
        userName = userName.trim(),
        serviceName = serviceName.trim(),
    )

    fun serviceOrNull(): String? = serviceName.trim().takeIf(String::isNotEmpty)

    companion object {
        /** Ограничение наше: `@Size` у бэкенда на этих полях нет. */
        const val MAX_NAME_LENGTH = 120

        /** Столько же: услугу человек называет словами, а не сочинением. */
        const val MAX_SERVICE_LENGTH = 200
    }
}

/** Что не так с запросом. Каждая ошибка привязана к своему полю. */
sealed interface WalkInRequestError {
    data object NameRequired : WalkInRequestError
    data class NameTooLong(val max: Int) : WalkInRequestError
    data class ServiceTooLong(val max: Int) : WalkInRequestError
}

/**
 * Проверка запроса до отправки.
 *
 * Ошибки возвращаются **все сразу**: форма короткая, но показывать замечания
 * по одному — это заставить нажимать кнопку дважды.
 *
 * Имя обязательно, потому что его требует бэкенд: мастер зовёт человека по
 * имени, номера в парикмахерской не выкликают.
 */
object WalkInRequestValidator {

    fun validate(request: WalkInRequest): List<WalkInRequestError> {
        val trimmed = request.trimmed()
        return buildList {
            when {
                trimmed.userName.isEmpty() -> add(WalkInRequestError.NameRequired)
                trimmed.userName.length > WalkInRequest.MAX_NAME_LENGTH ->
                    add(WalkInRequestError.NameTooLong(WalkInRequest.MAX_NAME_LENGTH))
            }
            if (trimmed.serviceName.length > WalkInRequest.MAX_SERVICE_LENGTH) {
                add(WalkInRequestError.ServiceTooLong(WalkInRequest.MAX_SERVICE_LENGTH))
            }
        }
    }
}
