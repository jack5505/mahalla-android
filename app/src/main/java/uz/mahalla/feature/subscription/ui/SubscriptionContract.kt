package uz.mahalla.feature.subscription.ui

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.subscription.domain.BillingPeriod
import uz.mahalla.feature.subscription.domain.Subscription
import uz.mahalla.feature.subscription.domain.SubscriptionCharge
import uz.mahalla.feature.subscription.domain.SubscriptionPlan
import uz.mahalla.feature.subscription.domain.SubscriptionRenewal
import uz.mahalla.feature.subscription.domain.canRenew
import java.time.Instant

/**
 * Состояние экрана подписки (issue #103).
 *
 * Тарифы и текущая подписка — **два независимых состояния**: это две ручки, и
 * отказ одной не повод спрятать другую. Отказ тарифов не должен прятать
 * подписку, за которую человек уже платит, а отказ подписки — список, ради
 * которого экран и открыт.
 *
 * @param current текущая подписка. [ScreenState.Empty] — её нет, и это не
 * ошибка: у большинства подписки не будет никогда.
 * @param charges история списаний (задача 9.3) — третье независимое
 * состояние: это третья ручка, и её отказ не повод спрятать подписку.
 * @param now момент, от которого считается прогноз продления. Лежит в
 * состоянии, а не берётся в экране: `Instant.now()` в `@Composable` сделал бы
 * и экран, и превью недетерминированными.
 * @param pending действие, которое сейчас выполняется. Пока оно висит,
 * остальные кнопки заблокированы: ответы приезжали бы на состояние, которого
 * уже нет (то же правило, что у устройств в профиле, issue #61).
 * @param actionFailure отказ действия — текстом сервера (issue #34) над
 * списком, а не вместо него: тарифы уже на экране.
 * @param notice что удалось. Экран после успеха не уходит (уводить некуда), а
 * молчание читается как «ничего не произошло» — этому научил issue #49.
 */
data class SubscriptionState(
    val plans: ScreenState<List<SubscriptionPlan>> = ScreenState.Loading,
    val current: ScreenState<Subscription> = ScreenState.Loading,
    val charges: ScreenState<List<SubscriptionCharge>> = ScreenState.Loading,
    val period: BillingPeriod = BillingPeriod.Default,
    val isRefreshing: Boolean = false,
    val chargesHasMore: Boolean = false,
    val isLoadingMoreCharges: Boolean = false,
    val chargesLoadMoreFailure: ApiFailure? = null,
    val pending: SubscriptionAction? = null,
    val confirmCancel: Boolean = false,
    val actionFailure: ApiFailure? = null,
    val notice: SubscriptionNotice? = null,
    val now: Instant? = null,
) : UiState {

    /** Оформленная подписка, если она приехала. */
    val subscription: Subscription? get() = (current as? ScreenState.Content)?.data

    val isBusy: Boolean get() = pending != null

    /**
     * Продлевать есть что: подписка сама не продлится ([canRenew]) и её тариф
     * в списке нашёлся — без него неизвестно ни на что подписывать, ни какая
     * это ручка (у бизнес-тарифов своя).
     */
    val renewablePlan: SubscriptionPlan?
        get() {
            val subscription = subscription?.takeIf { it.canRenew } ?: return null
            return (plans as? ScreenState.Content)?.data
                ?.firstOrNull { it.isSameCode(subscription.planCode) }
                ?.takeIf { it.isPaid }
        }

    val isRenewing: Boolean get() = pending == SubscriptionAction.Renew

    /**
     * Каким периодом продлевать: тем, что оплачен, а если сервер его не
     * назвал — выбранным на экране.
     */
    val renewalPeriod: BillingPeriod
        get() = subscription?.let { SubscriptionRenewal.periodOf(it, period) } ?: period

    /** Когда спишут в следующий раз; `null` — списания не будет. */
    val nextChargeAt: Instant?
        get() = subscription?.let(SubscriptionRenewal::nextChargeAt)

    /**
     * Прогноз «продление доведёт до». Без часов (`now == null`) прогноза нет:
     * дата, посчитанная от неизвестного момента, хуже её отсутствия.
     */
    val renewsUntil: Instant?
        get() {
            if (renewablePlan == null) return null
            val subscription = subscription ?: return null
            val moment = now ?: return null
            return SubscriptionRenewal.renewedUntil(subscription, renewalPeriod, moment)
        }

    /**
     * Пробный период предлагается только тому, у кого подписки ещё не было:
     * второй раз бэкенд его не даст, а кнопка, которая всегда кончается
     * отказом, хуже её отсутствия.
     */
    val trialAvailable: Boolean get() = current is ScreenState.Empty

    fun isSubscribing(planCode: String): Boolean =
        (pending as? SubscriptionAction.Subscribe)?.planCode == planCode

    fun isStartingTrial(planCode: String): Boolean =
        (pending as? SubscriptionAction.Trial)?.planCode == planCode
}

/** Что именно сейчас выполняется — от этого зависит, какая кнопка «крутится». */
sealed interface SubscriptionAction {
    data class Subscribe(val planCode: String) : SubscriptionAction
    data class Trial(val planCode: String) : SubscriptionAction

    /**
     * Продление. Отдельно от [Subscribe] при той же ручке: кнопка живёт в
     * карточке подписки, и «крутиться» должна она, а не карточка тарифа.
     */
    data object Renew : SubscriptionAction
    data object Cancel : SubscriptionAction
    data class AutoRenew(val enabled: Boolean) : SubscriptionAction
}

/** Что удалось: плашка на экране вместо молчания. */
enum class SubscriptionNotice {
    Subscribed,
    TrialStarted,
    Renewed,
    Cancelled,
}

sealed interface SubscriptionEvent : UiEvent {
    /**
     * Экран вернулся на передний план: подписку могли отменить или продлить в
     * другом месте, а срок идёт и без участия приложения.
     */
    data object ScreenResumed : SubscriptionEvent

    data object Refreshed : SubscriptionEvent
    data object Retry : SubscriptionEvent
    data object CurrentRetry : SubscriptionEvent
    data object ChargesRetry : SubscriptionEvent

    /** «Показать ещё» в истории списаний. */
    data object ChargesLoadMore : SubscriptionEvent

    data class PeriodSelected(val period: BillingPeriod) : SubscriptionEvent

    data class SubscribeClicked(val planCode: String) : SubscriptionEvent
    data class TrialClicked(val planCode: String) : SubscriptionEvent
    data object RenewClicked : SubscriptionEvent

    data object CancelRequested : SubscriptionEvent
    data object CancelConfirmed : SubscriptionEvent
    data object CancelDismissed : SubscriptionEvent

    data class AutoRenewToggled(val enabled: Boolean) : SubscriptionEvent

    data object NoticeDismissed : SubscriptionEvent
}

/**
 * Эффектов у экрана нет: он никуда не уводит и ничего не открывает снаружи.
 * Оформление, пробный период и отмена кончаются здесь же — плашкой и
 * обновлённой карточкой подписки.
 */
sealed interface SubscriptionEffect : UiEffect
