package uz.mahalla.feature.subscription.domain

import uz.mahalla.feature.wallet.domain.WalletAmounts

/**
 * Тариф подписки (`PlanResponse`, issue #103, эпик #13).
 *
 * Все суммы — целые сумы, как и везде в приложении
 * ([uz.mahalla.core.format.MoneyFormatter]): пересчёт из младших единиц
 * бэкенда делает [SubscriptionAmounts] на границе данных.
 *
 * @param code машинный код (`FREE`, `PRO`, …) — им же тариф оформляется и по
 * нему же сверяется с текущей подпиской. Тариф без кода до домена не доезжает:
 * подписаться на него нечем.
 * @param name название от бэкенда (не узбекское) и [nameUz] — узбекское.
 * Своих строк под названия тарифов в приложении нет намеренно: новый тариф
 * бэкенда иначе приехал бы безымянным.
 * @param yearlyDiscountPercent выгода годовой оплаты по версии сервера.
 * @param trialDays сколько дней пробного периода даёт тариф; `0` — не даёт.
 * @param features возможности-флаги, [maxPlaces] и соседи — числовые лимиты.
 * @param amountScale делитель, которым суммы этого тарифа переведены в сумы.
 * Хранится в домене по той же причине, что и у кошелька: если однажды
 * понадобится отправить сумму обратно, переводить её надо тем же делителем,
 * который вывела эта же выдача.
 */
data class SubscriptionPlan(
    val code: String,
    val name: String? = null,
    val nameUz: String? = null,
    val description: String? = null,
    val audience: PlanAudience = PlanAudience.User,
    val tier: String? = null,
    val monthlySum: Long = 0,
    val yearlySum: Long = 0,
    val amountScale: Long = WalletAmounts.TIYIN_IN_SOM,
    val yearlyDiscountPercent: Int = 0,
    val trialDays: Int = 0,
    val isFree: Boolean = false,
    val isPopular: Boolean = false,
    val features: Set<PlanFeature> = emptySet(),
    val maxPlaces: Int? = null,
    val maxListings: Int? = null,
    val maxPhotosPerListing: Int? = null,
    val freePromotionsMonthly: Int? = null,
    val analyticsLevel: String? = null,
) {

    /**
     * Название на языке приложения. Узбекское поле — не всегда заполнено,
     * поэтому пустое значение уступает второму имени, а не показывается
     * пустой строкой; нет ни одного — остаётся код тарифа: безымянная карточка
     * с ценой хуже машинного `PRO`.
     */
    fun displayName(uzbek: Boolean): String {
        val preferred = if (uzbek) nameUz else name
        val fallback = if (uzbek) name else nameUz
        return preferred?.takeIf { it.isNotBlank() }
            ?: fallback?.takeIf { it.isNotBlank() }
            ?: code
    }

    /** Цена за выбранный период — в сумах. */
    fun priceSum(period: BillingPeriod): Long = when (period) {
        BillingPeriod.Monthly -> monthlySum
        BillingPeriod.Yearly -> yearlySum
    }

    /**
     * Платный тариф. Флага сервера мало: тариф с нулевыми ценами платным быть
     * не может, чем бы ни было заполнено `isFree`.
     */
    val isPaid: Boolean get() = !isFree && (monthlySum > 0 || yearlySum > 0)

    /**
     * Пробный период предлагается только у платного тарифа: «попробовать
     * бесплатный бесплатно» — предложение без смысла, а сервер на него
     * ответил бы отказом.
     */
    val hasTrial: Boolean get() = trialDays > 0 && isPaid

    /**
     * Выгода годовой оплаты в процентах.
     *
     * Слово сервера старше своего расчёта — считает он, а показывать надо то,
     * что он же и спишет. Своё число нужно там, где поля нет вовсе: цены-то
     * приехали, и разница между ними видна и без сервера. Округляется **вниз**
     * — обещать выгоду больше настоящей нельзя (то же правило, что у скидки
     * промокода в «Еде»).
     */
    val savingsPercent: Int
        get() {
            val server = yearlyDiscountPercent
            if (server in 1..MAX_DISCOUNT_PERCENT) return server
            val yearOfMonthly = monthlySum * MONTHS_IN_YEAR
            if (yearOfMonthly <= 0 || yearlySum <= 0 || yearlySum >= yearOfMonthly) return 0
            return ((yearOfMonthly - yearlySum) * PERCENT / yearOfMonthly).toInt()
        }

    /** Тот же тариф, что уже оформлен: коды сравниваются без учёта регистра. */
    fun isSameCode(other: String?): Boolean =
        other != null && other.trim().equals(code, ignoreCase = true)

    companion object {
        const val MONTHS_IN_YEAR = 12L
        private const val PERCENT = 100L
        private const val MAX_DISCOUNT_PERCENT = 99
    }
}

/** Период оплаты. Значения — ровно те, что принимает `SubscribeRequest`. */
enum class BillingPeriod(val apiValue: String) {
    Monthly("MONTHLY"),
    Yearly("YEARLY"),
    ;

    companion object {
        val Default: BillingPeriod = Monthly

        /**
         * Период из ответа сервера. Незнакомое значение — `null`, а не
         * [Monthly]: «оплачено помесячно» там, где на самом деле год, — это
         * неверная дата следующего списания на экране.
         */
        fun fromServer(value: String?): BillingPeriod? {
            val normalized = value?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.apiValue == normalized }
        }
    }
}

/**
 * Кому тариф предназначен. От этого зависит и запрос списка
 * (`plans?audience=…`), и ручка оформления: у бизнес-тарифов своя
 * (`subscriptions/business/subscribe`).
 *
 * Незнакомое значение — [Unknown]: такой тариф показывается, но оформляется
 * обычной ручкой. Спрятать его значило бы скрыть от человека тариф, который
 * сервер ему же и предложил.
 */
enum class PlanAudience(val apiValue: String) {
    User("USER"),
    Business("BUSINESS"),
    Unknown(""),
    ;

    companion object {
        fun fromServer(value: String?): PlanAudience {
            val normalized = value?.trim()?.uppercase().orEmpty()
            if (normalized.isEmpty()) return User
            return entries.firstOrNull { it.apiValue == normalized } ?: Unknown
        }
    }
}

/**
 * Возможности тарифа — флаги `PlanResponse`. Перечислением, а не набором
 * `Boolean`: порядок объявления и есть порядок в списке на карточке, и он
 * одинаков у всех тарифов — иначе колонки сравнивать невозможно.
 *
 * Числовые лимиты (`maxPlaces`, `maxListings`, …) сюда не входят: у них есть
 * значение, а не только «да/нет».
 */
enum class PlanFeature {
    NoAds,
    VerifiedBadge,
    FeaturedListing,
    PrioritySupport,
    MultiStaff,
    CustomBranding,
    ApiAccess,
}

/**
 * Единица цен тарифа (тот же приём, что у кошелька в issue #62).
 *
 * Бэкенд отдаёт каждую цену дважды — целым числом (`monthlyPrice`) и дробным
 * «в сумах» (`monthlyPriceSom`), — а что за единица у целого поля, схема не
 * говорит. Делитель поэтому не зашивается, а выводится из самой пары.
 *
 * Пар две, и это важно: у тарифа вполне может быть только годовая цена
 * (месячная — ноль), и тогда месячная пара ничего не доказывает. Берётся
 * первая пара, у которой оба числа ненулевые; нет ни одной (бесплатный тариф)
 * — делитель не важен, ноль остаётся нулём.
 */
object SubscriptionAmounts {

    fun scaleOf(
        monthly: Long?,
        monthlySom: Double?,
        yearly: Long?,
        yearlySom: Double?,
    ): Long = when {
        isConclusive(monthly, monthlySom) -> WalletAmounts.scaleOf(monthly, monthlySom)
        isConclusive(yearly, yearlySom) -> WalletAmounts.scaleOf(yearly, yearlySom)
        else -> WalletAmounts.TIYIN_IN_SOM
    }

    private fun isConclusive(minor: Long?, som: Double?): Boolean =
        minor != null && som != null && minor != 0L && som != 0.0
}
