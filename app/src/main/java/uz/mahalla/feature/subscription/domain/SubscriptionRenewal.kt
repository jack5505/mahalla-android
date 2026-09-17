package uz.mahalla.feature.subscription.domain

import uz.mahalla.core.format.DateTimeFormatters
import java.time.Instant
import java.time.ZoneId

/**
 * Даты продления (эпик #13, задачи 9.2 и 9.3).
 *
 * Что здесь **не** считается: срок подписки, остаток дней и списанная сумма —
 * это слово сервера (`expiresAt`, `daysRemaining`, `pricePaid`), и второй
 * арифметики у них быть не должно.
 *
 * Что считается: **дата следующего списания** — это и есть `expiresAt`, взятая
 * тогда, когда списание вообще будет (иначе её показывать нельзя), — и
 * **прогноз «продлим до»** для кнопки продления. Прогноз нужен потому, что
 * ответа на «а до какого числа?» до самого запроса не существует: `subscribe`
 * возвращает срок только вместе со списанием. Поэтому он и подписан как
 * прогноз, а после ответа сервера экран показывает уже его дату.
 *
 * Месяц и год прибавляются календарно, в зоне приложения: «месяц» после 31
 * января — это 28 февраля, а не 3 марта, и `Instant.plus(30 дней)` тут врал бы.
 */
object SubscriptionRenewal {

    /**
     * Когда с человека спишут в следующий раз.
     *
     * `null` — списания не будет: автопродление выключено либо подписку и так
     * надо продлевать руками ([canRenew]). У истёкшей подписки `expiresAt` —
     * дата в прошлом, и назвать её «следующим списанием» значило бы соврать; в
     * грейс-периоде срок списания решает бэкенд, и приложение его не знает.
     */
    fun nextChargeAt(subscription: Subscription): Instant? =
        subscription.expiresAt?.takeIf { subscription.autoRenew && !subscription.canRenew }

    /**
     * До какого момента продление доведёт подписку.
     *
     * Отсчёт — от конца оплаченного срока, если он ещё не наступил (в
     * грейс-периоде бэкенд может считать сроком конец грейса), иначе от [now]:
     * у истёкшей подписки прибавлять к прошлогодней дате нечего.
     *
     * **У отменённой подписки отсчёт всегда от [now]**, даже когда доступ по
     * ней ещё есть. Причина та же, по которой у действующей подписки нет
     * кнопки продления ([canRenew]): контракт не обещает, что `subscribe`
     * *прибавит* срок к оплаченному. Обещать «до» на месяц позже настоящего
     * нельзя — это то же правило, что у выгоды годовой оплаты, которая
     * округляется вниз.
     *
     * @param period период оплаты. Берётся у самой подписки, а не с экрана:
     * продлевают то, что уже оплачено. Период неизвестен — решает вызывающий.
     */
    fun renewedUntil(
        subscription: Subscription,
        period: BillingPeriod,
        now: Instant,
        zone: ZoneId = DateTimeFormatters.AppZone,
    ): Instant {
        val paidUntil = subscription.expiresAt
            ?.takeIf { it.isAfter(now) && subscription.status != SubscriptionStatus.Cancelled }
            ?: now
        val start = paidUntil.atZone(zone)
        val end = when (period) {
            BillingPeriod.Monthly -> start.plusMonths(1)
            BillingPeriod.Yearly -> start.plusYears(1)
        }
        return end.toInstant()
    }

    /**
     * Каким периодом продлевать: тем, что уже оплачен, а если сервер его не
     * назвал — тем, что выбран на экране. Гадать «помесячно» нельзя: у годовой
     * подписки это и не та цена, и не та дата.
     */
    fun periodOf(subscription: Subscription, selected: BillingPeriod): BillingPeriod =
        subscription.billingPeriod ?: selected
}
