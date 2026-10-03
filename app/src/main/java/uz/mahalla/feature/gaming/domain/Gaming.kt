package uz.mahalla.feature.gaming.domain

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.TableBar
import androidx.compose.material.icons.outlined.Vrpano
import androidx.compose.ui.graphics.vector.ImageVector
import uz.mahalla.R
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Игровая зона заведения (эпик #11, issue #98, #406).
 *
 * Схема `GamingZoneResponse` снята со стенда 2026-09-04, поля `totalUnits` и
 * закрытый `zoneType` — повторно curl'ом 2026-10-02 после выката
 * jack5505/mahalla#363: `id, placeId, name, description, zoneType,
 * pricePerHour, totalUnits, isAvailable`.
 *
 * @param pricePerHour цена часа в **сумах**. Бэкенд отдаёт её в тийинах,
 * пересчёт делает маппер (`Money.tiyinToSom`, issue #149); [totalPrice]
 * считается уже из сум, в тело брони деньги не уходят.
 * @param totalUnits реальных мест в зоне (было `totalSeats` — оценка
 * вместимости, issue #406). `null` — сервер не прислал: показывать «0 мест»
 * вместо молчания значило бы соврать.
 * @param isAvailable зона открыта для брони. Молчание сервера — «закрыта»
 * (правило `MyPlace`, issue #94): предложить бронь того, о чём ничего не
 * известно, хуже, чем не предложить.
 */
data class GamingZone(
    val id: String,
    val placeId: String,
    val name: String = "",
    val description: String? = null,
    val zoneType: GamingZoneType? = null,
    val pricePerHour: Long = 0,
    val totalUnits: Int? = null,
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
 * Тип зоны (`GamingZoneResponse.zoneType`, issue #406) — закрытый справочник
 * с выката jack5505/mahalla#363. Раньше поле было произвольной строкой.
 *
 * [labelRes] и [icon] подписывают зону на карточке и место в шторке одной и
 * той же строкой (issue #406, задача): «PC» → «Kompyuter», и то же слово у
 * каждого места этой зоны в списке. [Billiards] и [TableTennis] делят одну
 * подпись («Stol»/«Стол») — задача прямо требует разводить их только по
 * `apiValue`, не по тексту: два вида стола, одно слово.
 *
 * [Other] — одновременно и настоящее значение справочника, и фоллбек для
 * значения, которого приложение ещё не знает: новый тип в будущем не должен
 * падать на `enumValueOf`, а должен выглядеть как «прочее» (то же решение,
 * что у [GamingBookingStatus.Unknown], но здесь оно не отдельное значение —
 * сервер сам завёл `OTHER` для не-технических зон).
 */
enum class GamingZoneType(
    val apiValue: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    Pc("PC", R.string.gaming_zone_type_pc, Icons.Outlined.Computer),
    Console("CONSOLE", R.string.gaming_zone_type_console, Icons.Outlined.SportsEsports),
    Vr("VR", R.string.gaming_zone_type_vr, Icons.Outlined.Vrpano),

    // Бэкенд не отдаёт отдельного значка для бильярда — закрытого набора
    // иконок на двенадцать типов спорта не напасёшься, а стол есть стол.
    Billiards("BILLIARDS", R.string.gaming_zone_type_table, Icons.Outlined.TableBar),
    TableTennis("TABLE_TENNIS", R.string.gaming_zone_type_table, Icons.Outlined.TableBar),
    Other("OTHER", R.string.gaming_zone_type_other, Icons.Outlined.Category),
    ;

    companion object {
        /** `null` — сервер не прислал тип; значения вне справочника — [Other]. */
        fun fromApi(value: String?): GamingZoneType? {
            val normalized = value?.trim()?.uppercase(Locale.ROOT)
            if (normalized.isNullOrEmpty()) return null
            return entries.firstOrNull { it.apiValue == normalized } ?: Other
        }
    }
}

/**
 * Место зоны (`GamingUnitResponse`, issue #406) — нумерованная позиция, по
 * которым бэкенд считает [GamingZone.totalUnits].
 *
 * Выбора места в этом шаге ещё нет (см. `GamingApi.units`): список — только
 * справочник в шторке брони, бронь по-прежнему уходит `zoneId`.
 *
 * @param seats посадочных мест **в этом месте**. `1` — обычное место, больше —
 * кабина на несколько человек (бронируется целиком, отдельных мест внутри
 * бэкенд не заводит).
 */
data class GamingUnit(
    val id: String,
    val zoneId: String,
    val number: Int,
    val seats: Int = 1,
) {

    /** `seats > 1` — кабина, а не одиночное место; подпись за это и цепляется. */
    val isCabin: Boolean get() = seats > 1
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
 * и такая строка означает местное ташкентское время слота, а не UTC —
 * разбирает `parseServerSlotInstant` (issue #144).
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
         * Ограничение наше и **строже серверного**: в схеме `GamingBookRequest`
         * `durationHours` — целое от 1 до 24 (сверено 2026-09-10). Восемь часов
         * — смена в компьютерном клубе; больше похоже на опечатку, а платит
         * человек настоящими деньгами.
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
