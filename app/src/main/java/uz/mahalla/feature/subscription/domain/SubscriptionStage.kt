package uz.mahalla.feature.subscription.domain

/**
 * Состояние подписки так, как его читает человек (эпик #13, задача 9.3).
 *
 * Отдельно от [SubscriptionStatus]: тот — пересказ поля `status` бэкенда, а
 * здесь собрано всё, что сервер сказал о подписке разными полями (`status`,
 * `isActive`, `daysRemaining`, `inGracePeriod`). Разница видна на «истекает»:
 * такого статуса у бэкенда нет вовсе, а человеку это самое важное состояние —
 * дальше либо спишут деньги, либо доступ пропадёт.
 *
 * Своего расчёта от `expiresAt` здесь нет: остаток дней считает сервер (у него
 * есть грейс-период), и вторая арифметика разошлась бы с ним в самый неудобный
 * момент.
 */
enum class SubscriptionStage {

    /** Действует, до конца срока далеко. */
    Active,

    /**
     * Действует, но кончается: осталось не больше [EXPIRING_SOON_DAYS] дней
     * либо оплаченный срок уже вышел и идёт грейс-период.
     */
    ExpiringSoon,

    /** Не действует: срок вышел, грейс кончился. */
    Expired,

    /** Отменена. Доступ при этом может остаться до конца оплаченного срока. */
    Cancelled,

    /**
     * Сервер сказал что-то незнакомое. Не «истекла»: новый статус бэкенда не
     * должен превращаться в «доступа нет» у всех подряд.
     */
    Unknown,
    ;

    companion object {
        /** За сколько дней до конца подписка считается истекающей. */
        const val EXPIRING_SOON_DAYS = 3L
    }
}

/**
 * Состояние подписки для экрана. Порядок проверок — от самого определённого к
 * самому мягкому:
 *
 * 1. `EXPIRED` от сервера — истекла, что бы ни было в остальных полях;
 * 2. `CANCELLED` — отменена (доступ мог остаться, но продлевать её не будут);
 * 3. `isActive == false` без грейс-периода — сервер сам сказал, что доступа
 *    нет, даже если статус незнакомый;
 * 4. грейс-период или остаток не больше [SubscriptionStage.EXPIRING_SOON_DAYS]
 *    дней — истекает;
 * 5. `ACTIVE` — активна;
 * 6. всё прочее — [SubscriptionStage.Unknown].
 */
val Subscription.stage: SubscriptionStage
    get() = when {
        status == SubscriptionStatus.Expired -> SubscriptionStage.Expired
        status == SubscriptionStatus.Cancelled -> SubscriptionStage.Cancelled
        !isActive && !inGracePeriod -> SubscriptionStage.Expired
        inGracePeriod -> SubscriptionStage.ExpiringSoon
        daysRemaining != null &&
            daysRemaining <= SubscriptionStage.EXPIRING_SOON_DAYS -> SubscriptionStage.ExpiringSoon

        status == SubscriptionStatus.Active -> SubscriptionStage.Active
        else -> SubscriptionStage.Unknown
    }

/**
 * Подписку стоит продлить руками: сама она не продлится.
 *
 * Это ровно три случая — доступа уже нет (`isActive == false`), оплаченный
 * срок кончился и идёт грейс, либо подписка отменена (тогда автопродление
 * выключено бэкендом, и по концу срока она просто пропадёт).
 *
 * У действующей подписки кнопки продления нет намеренно: `POST
 * subscriptions/subscribe` из контракта не обещает **прибавить** срок к
 * оплаченному, и «продление», обнулившее оплаченные дни, — это потерянные
 * деньги. Действующая подписка продлевается автопродлением, у него для этого
 * своя ручка.
 */
val Subscription.canRenew: Boolean
    get() = status == SubscriptionStatus.Cancelled || !isActive || inGracePeriod
