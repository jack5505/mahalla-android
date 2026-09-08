package uz.mahalla.feature.promotions.domain

import java.time.Instant

/**
 * Акции (issue #104, контроллер `promotion`).
 *
 * Контракт снят со стенда (`/v3/api-docs` + curl'ы): схема `Promotion` в
 * документации встречается один раз — коллизии springdoc, из-за которой поля
 * заявки заведения приходилось выводить из ответа (issue #84), здесь нет.
 *
 * Суммы приходят целыми числами без дробного близнеца (пара
 * `balance`/`balanceSom` есть только в кошельке, issue #62), поэтому считаем
 * их сумами — как в «Еде» (issue #9) и в брони (issue #97).
 */
data class Promotion(
    val id: String,
    /** Заголовок, который пишет заведение. Пустым не бывает — см. маппер. */
    val title: String,
    val description: String? = null,
    val type: PromoType = PromoType.Unknown,
    /** `null` — акция платформы: конкретного заведения у неё нет. */
    val placeId: String? = null,
    /** 1..100; всё остальное сервер прислал по ошибке и показывать это нечем. */
    val discountPercent: Int? = null,
    /** Сумы. Ноль и отрицательное — не скидка. */
    val discountAmount: Long? = null,
    val minOrderAmount: Long? = null,
    val promoCode: String? = null,
    val startsAt: Instant? = null,
    val endsAt: Instant? = null,
    val isPlatformWide: Boolean = false,
    /** `isActive` бэкенда. Молчание — не «выключена». */
    val isActive: Boolean = true,
    /** Вердикт бэкенда `valid`. `null` — он о нём промолчал. */
    val isValid: Boolean? = null,
) {

    /**
     * Показывать акцию или нет.
     *
     * Истёкшая акция — обещание скидки, которой уже нет, и это хуже пустого
     * блока. Поэтому проверяются оба слова сервера (`isActive`, `valid`) и
     * обе границы срока; отсутствие любого из них препятствием не считается —
     * молчание не повод прятать то, что заведение завело.
     */
    fun isLiveAt(now: Instant): Boolean = when {
        !isActive -> false
        isValid == false -> false
        startsAt != null && now.isBefore(startsAt) -> false
        // Момент окончания уже не входит в срок.
        endsAt != null && !now.isBefore(endsAt) -> false
        else -> true
    }

    /**
     * Нажимается только то, у чего есть последствие: акция без заведения
     * никуда не ведёт и притворяться кликабельной не должна (то же правило,
     * что у уведомлений в issue #81).
     */
    val isTappable: Boolean get() = PromotionTarget.of(this) != PromotionTarget.None
}

/** Страница акций платформы: пагинация у `promotions/platform` настоящая. */
data class PromotionPage(
    val items: List<Promotion> = emptyList(),
    val hasMore: Boolean = false,
)

/**
 * Вид акции. Незнакомое значение — [Unknown], а не «скидка»: выдумывать
 * условия за заведение нельзя, текст акции всё равно пишет оно само.
 */
enum class PromoType(val serverValue: String) {
    PercentOff("PERCENT_OFF"),
    FixedOff("FIXED_OFF"),
    BuyXGetY("BUY_X_GET_Y"),
    FreeDelivery("FREE_DELIVERY"),
    HappyHour("HAPPY_HOUR"),
    FlashSale("FLASH_SALE"),
    Unknown("");

    companion object {
        fun fromServer(raw: String?): PromoType {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return Unknown
            return entries.firstOrNull { it.serverValue.equals(value, ignoreCase = true) }
                ?: Unknown
        }
    }
}

/**
 * Куда ведёт акция. Правило одно и то же, что у уведомлений (issue #81):
 * переход есть только там, где известно, **чем именно** является цель. Для
 * акции это заведение — экрана самой акции в приложении нет, а вести на
 * «все акции заведения» некуда.
 */
sealed interface PromotionTarget {

    data class Place(val placeId: String) : PromotionTarget

    /** Акция платформы без заведения: остаётся текстом в списке. */
    data object None : PromotionTarget

    companion object {
        fun of(promotion: Promotion): PromotionTarget =
            promotion.placeId?.takeIf(String::isNotBlank)?.let(::Place) ?: None
    }
}

/** Правила блока акций: что и сколько показывать. */
object PromotionFeed {

    /**
     * Сколько акций платформы просить у сервера. Больше, чем помещается в
     * блок: часть первой страницы может оказаться просроченной, и запрос ровно
     * под размер блока оставлял бы главную без акций из-за одной истёкшей.
     */
    const val HOME_PAGE_SIZE = 10

    /** Блок на главной, а не лента: ниже него ещё «рядом» и «рекомендуем». */
    const val HOME_LIMIT = 5

    /** Действующие сейчас, в порядке сервера. */
    fun live(promotions: List<Promotion>, now: Instant): List<Promotion> =
        promotions.filter { it.isLiveAt(now) }

    /** То же для главной, но не длиннее блока. */
    fun home(promotions: List<Promotion>, now: Instant): List<Promotion> =
        live(promotions, now).take(HOME_LIMIT)
}
