package uz.mahalla.feature.subscription.domain

import java.time.Instant

/**
 * Списание за подписку (`PaymentTransaction`, `GET payments/transactions`,
 * эпик #13, задача 9.3).
 *
 * Отдельной ручки «история списаний по подписке» у бэкенда нет: платежи
 * приезжают все вместе, а к подписке относятся те, у которых [purpose]
 * говорит о подписке (`SUBSCRIPTION`, `SUBSCRIPTION_RENEWAL`, …). Фильтр
 * поэтому на клиенте — см. [isSubscriptionPurpose].
 *
 * @param amountSum сколько списали — в сумах.
 * @param errorMessage почему не прошло. Показывается как есть: свой текст
 * приложение придумать не может, а «платёж не прошёл» без причины — это
 * вопрос в поддержку.
 */
data class SubscriptionCharge(
    val id: String,
    val amountSum: Long = 0,
    val status: ChargeStatus = ChargeStatus.Unknown,
    val provider: ChargeProvider = ChargeProvider.Unknown,
    val purpose: String? = null,
    val errorMessage: String? = null,
    val createdAt: Instant? = null,
) {

    companion object {

        /**
         * Платёж относится к подписке. Сравнение — по подстроке: в схеме
         * `purpose` описан как `string`, то есть конкретные значения бэкенд не
         * фиксирует, и `SUBSCRIPTION_RENEWAL` рядом с `SUBSCRIPTION` пропасть
         * из истории не должен.
         */
        fun isSubscriptionPurpose(purpose: String?): Boolean =
            purpose?.trim()?.uppercase()?.contains(SUBSCRIPTION_PURPOSE) == true

        private const val SUBSCRIPTION_PURPOSE = "SUBSCRIPTION"
    }
}

/**
 * Исход платежа. Значения перечислены в схеме (`PENDING`, `PAID`, `FAILED`,
 * `CANCELLED`, `REFUNDED`), но незнакомое всё равно принимается как
 * [Unknown]: новый исход не должен превращаться в «не прошло».
 */
enum class ChargeStatus {
    Pending,
    Paid,
    Failed,
    Cancelled,
    Refunded,
    Unknown,
    ;

    companion object {

        private val PENDING = setOf("PENDING", "PROCESSING", "CREATED")
        private val PAID = setOf("PAID", "COMPLETED", "SUCCESS", "SUCCEEDED")
        private val FAILED = setOf("FAILED", "ERROR", "REJECTED")
        private val CANCELLED = setOf("CANCELLED", "CANCELED")
        private val REFUNDED = setOf("REFUNDED", "REVERSED")

        fun fromServer(value: String?): ChargeStatus = when (value?.trim()?.uppercase().orEmpty()) {
            in PENDING -> Pending
            in PAID -> Paid
            in FAILED -> Failed
            in CANCELLED -> Cancelled
            in REFUNDED -> Refunded
            else -> Unknown
        }
    }
}

/**
 * Чем платили. Набор — из схемы (`PAYME`, `CLICK`, `UZUM`, `CASH`);
 * незнакомый провайдер строку истории не прячет, просто остаётся без названия.
 */
enum class ChargeProvider(val apiValue: String) {
    Payme("PAYME"),
    Click("CLICK"),
    Uzum("UZUM"),
    Cash("CASH"),
    Unknown(""),
    ;

    companion object {

        fun fromServer(value: String?): ChargeProvider {
            val normalized = value?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.apiValue.isNotEmpty() && it.apiValue == normalized }
                ?: Unknown
        }
    }
}

/**
 * Страница истории списаний.
 *
 * @param hasMore у сервера есть ещё страницы платежей — не обязательно с
 * подписочными: фильтр по [SubscriptionCharge.isSubscriptionPurpose] клиентский.
 * @param nextPage с какой страницы сервера продолжать догрузку. Считается на
 * границе данных, потому что одна порция истории подписки может занять
 * несколько серверных страниц.
 */
data class SubscriptionChargePage(
    val items: List<SubscriptionCharge> = emptyList(),
    val hasMore: Boolean = false,
    val nextPage: Int = 0,
)
