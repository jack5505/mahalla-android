package uz.mahalla.feature.activity.domain

import java.time.Instant
import java.util.Locale

/**
 * Источник активности (issue #73, задача T7).
 *
 * Пять независимых ручек бэкенда, каждая со своей пагинацией и своим набором
 * статусов. Перечисление нужно не для красоты: по нему экран отмечает
 * **сбойный раздел** при частичном отказе — «брони не загрузились» полезнее,
 * чем «что-то не загрузилось».
 *
 * [Orders] — общий `GET orders`, а не `food/orders/my` из формулировки задачи:
 * у общего эндпоинта ответ описан схемой `OrderView` однозначно и без
 * коллизий, а у food-ручки имя схемы (`FoodOrderResponse`) раньше было
 * перекрыто коллизией springdoc (под ним лежал заказ фрилансера); сверка
 * 2026-09-10 разнесла имена по вертикалям (issue #235), но решение не
 * изменилось — то же самое уже принято для чтения одного заказа (issue #9).
 * Заодно один запрос вместо нескольких: `GET orders` отдаёт все вертикали
 * сразу, включая `CLOTHING` и `PHARMACY`.
 */
enum class ActivitySource {
    Orders,
    GamingBookings,
    MasterAppointments,
    DoctorAppointments,
    CinemaTickets,
}

/**
 * Что это за активность — для заголовка и иконки строки.
 *
 * Названия заведения ни один из пяти ответов не содержит (в `OrderView` есть
 * только `placeId`), поэтому заголовок строки — это вид активности, а не
 * место. Врать про «Osh Markazi» там, где сервер прислал один UUID, нельзя, а
 * показывать сам UUID незачем.
 */
enum class ActivityKind {
    FoodOrder,
    ClothingOrder,
    PharmacyOrder,
    CinemaOrder,
    GamingOrder,
    /** Заказ вертикали, которой приложение ещё не знает. */
    OtherOrder,
    GamingBooking,
    MasterAppointment,
    DoctorAppointment,
    CinemaTicket,
    ;

    companion object {

        /**
         * Вид заказа по вертикали из `OrderView`. Незнакомая вертикаль —
         * [OtherOrder], а не пропуск записи: заказ есть, деньги списаны, и
         * спрятать его из списка хуже, чем назвать общим словом.
         */
        fun ofOrderVertical(vertical: String?): ActivityKind =
            when (vertical?.trim()?.uppercase(Locale.ROOT)) {
                "FOOD" -> FoodOrder
                "CLOTHING" -> ClothingOrder
                "PHARMACY" -> PharmacyOrder
                "CINEMA" -> CinemaOrder
                "GAMING" -> GamingOrder
                else -> OtherOrder
            }
    }
}

/**
 * Состояние активности, приведённое к общему виду.
 *
 * У каждого источника своё перечисление статусов (`NEW…REFUNDED` у заказов,
 * `CONFIRMED…CANCELLED` у броней, `PENDING…NO_SHOW` у записей,
 * `ACTIVE…REFUNDED` у билетов), а фильтр «активные / история» и цвет бейджа в
 * списке — одни на всех. Приводить их к общему виду в UI значило бы
 * разложить четыре перечисления по `when` в композабле, где это не
 * проверишь тестом.
 *
 * [Unknown] обязателен и считается **активным**: набор статусов задаёт
 * бэкенд, и новое значение не должно ни ронять экран, ни прятать живую
 * активность в историю (то же правило, что у `OrderStatus.Unknown` в
 * вертикали «Еда»).
 */
enum class ActivityStatus {
    /** Создано, заведение ещё не подтвердило (`NEW`, `PENDING`). */
    Placed,

    /** Подтверждено и ждёт своего времени (`ACCEPTED`, `CONFIRMED`). */
    Confirmed,

    /** Идёт прямо сейчас (`PREPARING` у заказа, `ACTIVE` у брони). */
    InProgress,

    /** Готово к выдаче (`READY`). */
    Ready,

    /** В доставке (`IN_DELIVERY`). */
    OnTheWay,

    /** Закончено штатно (`DELIVERED`, `COMPLETED`, `USED`). */
    Completed,

    Cancelled,

    Refunded,

    /** Человек не пришёл (`NO_SHOW`) — не отмена, но и не выполнено. */
    Missed,

    Unknown,
    ;

    /**
     * Активность ещё чего-то ждёт от человека или от заведения. Именно это
     * различает две вкладки списка.
     */
    val isActive: Boolean
        get() = when (this) {
            Placed, Confirmed, InProgress, Ready, OnTheWay, Unknown -> true
            Completed, Cancelled, Refunded, Missed -> false
        }

    companion object {

        private fun normalize(value: String?): String? =
            value?.trim()?.uppercase(Locale.ROOT)?.replace('-', '_')?.takeIf { it.isNotEmpty() }

        /** `OrderStatus` бэкенда: `NEW`…`REFUNDED`. */
        fun ofOrder(value: String?): ActivityStatus = when (normalize(value)) {
            "NEW" -> Placed
            "ACCEPTED" -> Confirmed
            "PREPARING" -> InProgress
            "READY" -> Ready
            "IN_DELIVERY" -> OnTheWay
            "DELIVERED" -> Completed
            "CANCELLED" -> Cancelled
            "REFUNDED" -> Refunded
            else -> Unknown
        }

        /** `GamingBooking.status`: `CONFIRMED`, `ACTIVE`, `COMPLETED`, `CANCELLED`. */
        fun ofBooking(value: String?): ActivityStatus = when (normalize(value)) {
            "CONFIRMED" -> Confirmed
            "ACTIVE" -> InProgress
            "COMPLETED" -> Completed
            "CANCELLED" -> Cancelled
            else -> Unknown
        }

        /**
         * `AppointmentResponse.status`: `PENDING`, `CONFIRMED`, `CANCELLED`,
         * `COMPLETED`, `NO_SHOW`. Одна и та же схема у записи к мастеру и к
         * врачу.
         */
        fun ofAppointment(value: String?): ActivityStatus = when (normalize(value)) {
            "PENDING" -> Placed
            "CONFIRMED" -> Confirmed
            "CANCELLED" -> Cancelled
            "COMPLETED" -> Completed
            "NO_SHOW" -> Missed
            else -> Unknown
        }

        /**
         * `CinemaTicket.status`: `ACTIVE`, `USED`, `CANCELLED`, `REFUNDED`.
         *
         * `ACTIVE` у билета — не «идёт сейчас», а «действителен»: сеанс ещё не
         * начался или начался, но билет не погашен. Поэтому [Confirmed], а не
         * [InProgress] — иначе бейдж говорил бы про процесс, которого нет.
         */
        fun ofTicket(value: String?): ActivityStatus = when (normalize(value)) {
            "ACTIVE" -> Confirmed
            "USED" -> Completed
            "CANCELLED" -> Cancelled
            "REFUNDED" -> Refunded
            else -> Unknown
        }
    }
}

/**
 * Куда ведёт строка списка.
 *
 * Правило то же, что у `NotificationTarget` (issue #81): экран открывается
 * только там, где он есть и где известно, чем именно является идентификатор.
 * Нажатие без последствий читается как сломанный экран, а переход по чужому
 * id — как «заказ не найден».
 */
sealed interface ActivityTarget {

    /**
     * Статус заказа вертикали «Еда» (`OrderStatusRoute`).
     *
     * Только «Еда»: экран статуса построен на её домене — он показывает
     * этапы кухни и умеет «повторить заказ», возвращая позиции в **корзину
     * еды**. Заказ одежды или аптеки открылся бы там под видом заказа еды.
     */
    data class FoodOrder(val orderId: String) : ActivityTarget

    /** Карточка билета (issue #183): `GET cinema/tickets/{id}`. */
    data class CinemaTicket(val ticketId: String) : ActivityTarget

    /** Карточка записи к мастеру (issue #183): `GET appointments/{id}`. */
    data class MasterAppointment(val appointmentId: String) : ActivityTarget

    /** Карточка записи к врачу (issue #183): `GET hospitals/appointments/{id}`. */
    data class DoctorAppointment(val appointmentId: String) : ActivityTarget

    /** Экрана для этой активности пока нет — строка не кликабельна. */
    data object None : ActivityTarget
}

/**
 * Одна строка списка «мои активности»: заказ, бронь, запись или билет.
 *
 * @param id идентификатор внутри источника. Уникальность в списке даёт пара
 * [source] + [id]: у двух разных ручек бэкенда id могут совпасть, а ключ
 * `LazyColumn` обязан быть уникальным на весь список — см. [key].
 * @param occurredAt время, по которому список сортируется. Это **время
 * события**, если источник его сообщает (начало брони, дата записи), иначе
 * время создания: в списке активностей человек ищет «когда это», а не «когда
 * я нажал кнопку». `null` — сервер не прислал дату или прислала битую; такие
 * строки уходят в конец, а не наверх.
 * @param timeKind смысл [occurredAt] — прошлое или будущее (issue #174), см.
 * [ActivityTimeKind]. Нужен отдельной осью сортировки: сравнивать «когда
 * заказали» с «когда начнётся бронь» как одно число нельзя, см.
 * [ActivityMerge.filter].
 * @param amount сумма в сумах (бэкенд отдаёт тийины, пересчёт `Money.tiyinToSom`
 * в маппере, issue #149); `null` — источник её не сообщает.
 * @param note короткое уточнение под заголовком: номер заказа, место в зале,
 * название услуги. Всё, что бэкенд даёт человекочитаемым.
 * @param placeId заведение источника. Ни один из пяти ответов названия не
 * отдаёт (issue #150), только его — билет кино не отдаёт и его: у
 * `CinemaTicket` в ответе только `sessionId`. `null` — резолвить нечего.
 * @param placeName название заведения, дорезолвленное отдельным запросом
 * (issue #182, `GET places?ids=`) — бэкенд его напрямую не отдаёт нигде.
 * `null` — либо [placeId] нет, либо резолв не удался; строка в обоих случаях
 * остаётся с видом активности вместо названия, как до этой задачи.
 * @param placeLogoUrl логотип заведения, тем же запросом.
 */
data class Activity(
    val id: String,
    val source: ActivitySource,
    val kind: ActivityKind,
    val status: ActivityStatus,
    val occurredAt: Instant?,
    val timeKind: ActivityTimeKind = ActivityTimeKind.Recorded,
    val amount: Long? = null,
    val note: String? = null,
    val target: ActivityTarget = ActivityTarget.None,
    val placeId: String? = null,
    val placeName: String? = null,
    val placeLogoUrl: String? = null,
) {

    /** Ключ строки в `LazyColumn`: id уникален только внутри своей ручки. */
    val key: String get() = "${source.name}:$id"

    /** Кликабельна только та строка, у которой есть куда вести. */
    val isActionable: Boolean get() = target !is ActivityTarget.None
}

/** Вкладка списка. Третьей («все») нет намеренно — см. `ActivityFeed`. */
enum class ActivityFilter {
    Active,
    History,
}

/**
 * Что означает [Activity.occurredAt] — прошлое или будущее (issue #174).
 *
 * Источники сообщают время разного смысла: `Orders` и `CinemaTickets` — это
 * всегда `createdAt`, то есть прошлое (когда заказали); `GamingBookings` и
 * записи с известной датой — время самого события, то есть обычно будущее.
 * Смешивать их на одной оси возрастания значит класть старый заказ выше
 * записи, которая вот-вот начнётся — ровно баг из issue #174. Отдельное поле
 * вместо вывода смысла из [ActivitySource] нужно потому, что у записи к
 * мастеру/врачу смысл зависит от данных: `apptDate` есть — это [Event], нет —
 * это [Recorded] (см. `AppointmentDto.toActivity`).
 */
enum class ActivityTimeKind {
    /** Время в будущем (обычно): начало брони, дата записи. */
    Event,

    /** Время в прошлом: когда создана запись (заказ, покупка билета). */
    Recorded,
}

/**
 * Слияние источников (issue #73).
 *
 * Чистые функции, потому что цена ошибки здесь — пропавшая из списка
 * активность: заказ, за который человек заплатил, но не видит.
 */
object ActivityMerge {

    /**
     * Отбор по вкладке и сортировка.
     *
     * [ActivityFilter.History] — по убыванию, как любой список прошлого;
     * `occurredAt` там у всех источников только `createdAt` или прошедшая
     * дата события, так что одна ось не смешивает разные смыслы.
     *
     * [ActivityFilter.Active] смешивает: `Orders`/`CinemaTickets` несут
     * **прошлое** ([ActivityTimeKind.Recorded] — когда создана запись), а
     * `GamingBookings` и записи с известной датой — **будущее**
     * ([ActivityTimeKind.Event]). Одна ось возрастания клала бы старый заказ
     * выше брони, которая вот-вот начнётся (issue #174), поэтому сортировка —
     * две группы, а не одна:
     * 1. [ActivityTimeKind.Recorded] по убыванию (свежая запись сверху) — это
     *    то, что происходит прямо сейчас (готовится, обрабатывается), и у
     *    него нет известного будущего времени, чтобы ранжировать иначе;
     * 2. [ActivityTimeKind.Event] по возрастанию (ближайшее сверху);
     * 3. без даты — в самый конец, независимо от группы: наверху такая
     *    строка заняла бы место того, что человек как раз ищет.
     *
     * Внутри одинакового времени порядок задаёт [Activity.key] — иначе
     * строки переставлялись бы при каждой перезагрузке списка.
     */
    fun filter(items: List<Activity>, filter: ActivityFilter): List<Activity> {
        val selected = items.filter { it.status.isActive == (filter == ActivityFilter.Active) }
        if (filter == ActivityFilter.History) {
            return selected.sortedWith(
                compareBy<Activity> { it.occurredAt == null }
                    .thenByDescending { it.occurredAt?.toEpochMilli() ?: 0L }
                    .thenBy(Activity::key),
            )
        }
        return selected.sortedWith(
            compareBy<Activity> { activity ->
                when {
                    activity.occurredAt == null -> 2
                    activity.timeKind == ActivityTimeKind.Recorded -> 0
                    else -> 1
                }
            }
                .thenBy { activity ->
                    when (activity.timeKind) {
                        ActivityTimeKind.Recorded -> activity.occurredAt?.toEpochMilli()?.unaryMinus() ?: 0L
                        ActivityTimeKind.Event -> activity.occurredAt?.toEpochMilli() ?: 0L
                    }
                }
                .thenBy(Activity::key),
        )
    }

    /**
     * Догруженные страницы приклеиваются к уже показанным.
     *
     * Дедупликация по [Activity.key] обязательна: страницы пяти источников
     * приезжают в разное время, и активность, попавшая на границу страниц,
     * приедет дважды — в `LazyColumn` это дубликат ключа и падение.
     */
    fun append(current: List<Activity>, next: List<Activity>): List<Activity> {
        val known = current.mapTo(mutableSetOf(), Activity::key)
        return current + next.filter { known.add(it.key) }
    }
}
