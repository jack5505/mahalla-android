package uz.mahalla.feature.gaming.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Игровая зона заведения (эпик #11, issue #98).
 *
 * Схема `GamingZone` снята со стенда 2026-09-04 (`/v3/api-docs`):
 * `id, placeId, name, description, zoneType, pricePerHour, totalSeats,
 * isAvailable`. Коллизии springdoc у этого имени нет — в схеме оно
 * встречается один раз.
 *
 * @param pricePerHour цена часа. **Единица не подтверждена контрактом**:
 * поле целое и дробного близнеца (`*Som`, как у кошелька в issue #62) у него
 * нет. Считаем сумами, как в «Еде» (issue #9); если бэкенд считает тийины,
 * все цены окажутся в сто раз больше.
 * @param totalSeats мест в зоне. `null` — сервер не прислал: показывать
 * «0 мест» вместо молчания значило бы соврать.
 * @param isAvailable зона открыта для брони. Молчание сервера — «закрыта»
 * (правило `MyPlace`, issue #94): предложить бронь того, о чём ничего не
 * известно, хуже, чем не предложить.
 */
data class GamingZone(
    val id: String,
    val placeId: String,
    val name: String = "",
    val description: String? = null,
    val zoneType: String? = null,
    val pricePerHour: Long = 0,
    val totalSeats: Int? = null,
    val isAvailable: Boolean = false,
) {

    /**
     * Бронировать можно только открытую зону с известной ценой. Цена `0` —
     * это не «бесплатно», а молчание сервера: показать кнопку, которая приведёт
     * к счёту неизвестного размера, нельзя.
     */
    val isBookable: Boolean get() = isAvailable && pricePerHour > 0

    /** Сумма брони: цена часа × часы. Считается и показывается до отправки. */
    fun totalPrice(hours: Int): Long = pricePerHour * hours.coerceAtLeast(0)
}

/**
 * Состояние брони. Значения — перечисление бэкенда (`GamingBooking.status`):
 * `CONFIRMED`, `ACTIVE`, `COMPLETED`, `CANCELLED`.
 *
 * [Unknown] обязателен: набор состояний ведёт заведение из своей панели
 * (`bookings/{id}/complete` — эпик #16), и новое значение не должно ронять
 * список броней.
 */
enum class GamingBookingStatus(val apiValue: String) {
    /** Бронь принята, время ещё не наступило. */
    Confirmed("CONFIRMED"),

    /** Время идёт: человек в зоне. */
    Active("ACTIVE"),

    Completed("COMPLETED"),
    Cancelled("CANCELLED"),

    Unknown(""),
    ;

    /**
     * Бронь ещё в игре. [Unknown] активной **не** считается: рисовать
     * «предстоит» по незнакомому значению — то же самое, что придумать за
     * сервер.
     */
    val isActive: Boolean get() = this == Confirmed || this == Active

    companion object {
        fun fromApi(value: String?): GamingBookingStatus {
            val normalized = value?.trim()?.uppercase(Locale.ROOT)?.replace('-', '_')
                ?: return Unknown
            if (normalized.isEmpty()) return Unknown
            return entries.firstOrNull { it.apiValue == normalized } ?: Unknown
        }
    }
}

/**
 * Бронь игровой зоны (`GamingBooking`).
 *
 * @param zoneName имя зоны. В ответе его нет — ни в брони, ни в списке своих
 * броней, — поэтому оно подставляется из зоны, которую человек только что
 * выбрал, а в «моих бронях» остаётся пустым: подтягивать зоны каждого
 * заведения ради подписи значило бы сделать N запросов на экран.
 * @param startTime и [endTime] — `date-time`; Jackson отдаёт их и без зоны,
 * разбирает общий `parseServerInstant`.
 */
data class GamingBooking(
    val id: String,
    val zoneId: String = "",
    val placeId: String = "",
    val zoneName: String = "",
    val startTime: Instant? = null,
    val endTime: Instant? = null,
    val durationHours: Int? = null,
    val totalPrice: Long? = null,
    val status: GamingBookingStatus = GamingBookingStatus.Unknown,
)

/** Страница «моих броней». Правило подсчёта — [hasMore], как у issue #94. */
data class GamingBookingPage(
    val items: List<GamingBooking> = emptyList(),
    val hasMore: Boolean = false,
)

/**
 * Черновик брони: что человек выбрал в шторке.
 *
 * Время хранится моментом, а не строкой: слоты считаются от «сейчас», и
 * сравнивать выбор с живым временем нужно каждый раз заново — иначе выбранный
 * слот протухнет прямо на глазах (грабля `DeliverySlots` из эпика 5).
 */
data class GamingBookingDraft(
    val zoneId: String,
    val startTime: Instant? = null,
    val durationHours: Int = DEFAULT_HOURS,
) {
    companion object {
        const val DEFAULT_HOURS = 1
        const val MIN_HOURS = 1

        /**
         * Ограничение наше: у бэкенда его нет. Восемь часов — смена в
         * компьютерном клубе; больше похоже на опечатку, а платит человек
         * настоящими деньгами.
         */
        const val MAX_HOURS = 8
    }
}

/** Что не так с черновиком. Каждая причина — про своё поле. */
sealed interface GamingBookingError {
    /** Время не выбрано. */
    data object TimeRequired : GamingBookingError

    /** Выбранный слот уже прошёл, пока человек заполнял форму. */
    data object TimeTooSoon : GamingBookingError

    /** Часы вне [GamingBookingDraft.MIN_HOURS]..[GamingBookingDraft.MAX_HOURS]. */
    data class DurationOutOfRange(val min: Int, val max: Int) : GamingBookingError
}

/**
 * Проверка черновика до отправки. Причины возвращаются **все сразу**: форма
 * короткая, но показывать замечания по одному — заставлять нажимать кнопку
 * дважды.
 *
 * «Сейчас» приходит параметром, а не берётся внутри: время проверяется и при
 * открытии шторки, и при отправке, и оба раза от одного момента (тот же приём,
 * что в `CheckoutValidator` эпика 5).
 */
object GamingBookingValidator {

    fun validate(draft: GamingBookingDraft, now: Instant): List<GamingBookingError> = buildList {
        when {
            draft.startTime == null -> add(GamingBookingError.TimeRequired)
            draft.startTime.isBefore(now) -> add(GamingBookingError.TimeTooSoon)
        }
        if (draft.durationHours !in GamingBookingDraft.MIN_HOURS..GamingBookingDraft.MAX_HOURS) {
            add(
                GamingBookingError.DurationOutOfRange(
                    min = GamingBookingDraft.MIN_HOURS,
                    max = GamingBookingDraft.MAX_HOURS,
                ),
            )
        }
    }
}

/**
 * Слоты начала брони.
 *
 * Своих слотов бэкенд не отдаёт (ни расписания зоны, ни занятых интервалов в
 * контракте нет — см. `GamingApi`), поэтому список считается на клиенте:
 * получасовая сетка от ближайшего получаса после «сейчас».
 *
 * **Округление вверх обязательно**: слот, посчитанный вниз, окажется в
 * прошлом уже в момент показа, и сервер отверг бы собственное предложение
 * приложения (та же грабля, что у `DeliverySlots` эпика 5 — там на секундах).
 */
object GamingSlots {

    /** Шаг сетки. Полчаса — то, чем меряют время в игровых клубах. */
    val STEP: Duration = Duration.ofMinutes(30)

    /** Сколько слотов показывать: полсуток вперёд получасовой сеткой. */
    const val COUNT = 24

    fun next(
        now: Instant,
        zone: ZoneId,
        count: Int = COUNT,
        step: Duration = STEP,
    ): List<Instant> {
        if (count <= 0 || step.isZero || step.isNegative) return emptyList()
        val first = ceilTo(now, zone, step)
        return List(count) { index -> first.plus(step.multipliedBy(index.toLong())) }
    }

    /**
     * Ближайшая граница сетки не раньше [now]. Считается в местной зоне, а не
     * в UTC: получасовая сетка человека — это `12:00`, `12:30` по его часам.
     */
    private fun ceilTo(now: Instant, zone: ZoneId, step: Duration): Instant {
        val local = now.atZone(zone)
        // Секунды отбрасываются вниз, поэтому «ровно 12:00:00» слотом 12:00 и
        // остаётся, а «12:00:01» уезжает на 12:30.
        val truncated = local.truncatedTo(ChronoUnit.MINUTES)
        val stepMinutes = step.toMinutes()
        val minutesOfDay = truncated.hour * MINUTES_IN_HOUR + truncated.minute
        val remainder = minutesOfDay % stepMinutes
        val aligned = if (remainder == 0L && truncated.toInstant() == now) {
            truncated
        } else {
            truncated.plusMinutes(stepMinutes - remainder)
        }
        return aligned.toInstant()
    }

    private const val MINUTES_IN_HOUR = 60
}
