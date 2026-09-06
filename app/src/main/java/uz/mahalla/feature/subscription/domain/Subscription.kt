package uz.mahalla.feature.subscription.domain

import java.time.Instant

/**
 * Оформленная подписка (`SubscriptionResponse`, issue #103).
 *
 * @param planName название тарифа так, как его назвал сервер в ответе про
 * подписку. Своего перевода у него нет (в отличие от [SubscriptionPlan], где
 * приезжают оба имени), поэтому экран предпочитает имя из списка тарифов, а
 * это — фоллбэк на случай, когда список ещё не приехал или тариф из него
 * пропал.
 * @param pricePaidSum сколько списали — в сумах.
 * @param daysRemaining сколько дней осталось по версии сервера. Своего расчёта
 * от [expiresAt] нет: у бэкенда есть грейс-период, и считать его на клиенте
 * значило бы разойтись с ним в самый неудобный момент.
 * @param isActive подписка действует. Отдельно от [status]: сервер отдаёт оба
 * поля, и «ACTIVE, но уже не действует» (истёк срок) он различает сам.
 * @param inGracePeriod срок вышел, но доступ ещё есть.
 */
data class Subscription(
    val id: String? = null,
    val planCode: String? = null,
    val planName: String? = null,
    val status: SubscriptionStatus = SubscriptionStatus.Unknown,
    val billingPeriod: BillingPeriod? = null,
    val pricePaidSum: Long = 0,
    val startedAt: Instant? = null,
    val expiresAt: Instant? = null,
    val autoRenew: Boolean = false,
    val isTrial: Boolean = false,
    val daysRemaining: Long? = null,
    val isActive: Boolean = false,
    val inGracePeriod: Boolean = false,
) {

    /**
     * Отменять нечего у того, что уже отменено или истекло: кнопка там ведёт
     * только к отказу сервера.
     *
     * [SubscriptionStatus.Unknown] отменить **можно**: незнакомое значение —
     * это «неизвестно, чем кончилось», а не «кончилось», и запирать человека в
     * платной подписке из-за нового статуса бэкенда нельзя. Последнее слово
     * всё равно за сервером (то же правило, что у талона очереди в issue #96).
     */
    val canCancel: Boolean
        get() = status != SubscriptionStatus.Cancelled && status != SubscriptionStatus.Expired

    /**
     * Автопродление имеет смысл там же, где и отмена: у отменённой подписки
     * продлевать нечего, у истёкшей — тем более.
     */
    val canToggleAutoRenew: Boolean get() = canCancel
}

/**
 * Состояние подписки. Написание значений бэкенд в схеме не фиксирует (там
 * `string`), поэтому принимаются распространённые варианты, а незнакомое —
 * [Unknown]: новый статус не должен превращаться в «истекла» у всех подряд.
 */
enum class SubscriptionStatus {
    Active,
    Expired,
    Cancelled,
    Unknown,
    ;

    companion object {

        private val ACTIVE = setOf("ACTIVE", "TRIAL", "TRIALING", "GRACE", "GRACE_PERIOD")
        private val EXPIRED = setOf("EXPIRED", "ENDED", "INACTIVE")
        private val CANCELLED = setOf("CANCELLED", "CANCELED", "REVOKED")

        fun fromServer(value: String?): SubscriptionStatus =
            when (value?.trim()?.uppercase().orEmpty()) {
                in ACTIVE -> Active
                in EXPIRED -> Expired
                in CANCELLED -> Cancelled
                else -> Unknown
            }
    }
}
