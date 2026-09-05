package uz.mahalla.testutil

import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.subscription.data.SubscriptionRepository
import uz.mahalla.feature.subscription.domain.BillingPeriod
import uz.mahalla.feature.subscription.domain.PlanAudience
import uz.mahalla.feature.subscription.domain.Subscription
import uz.mahalla.feature.subscription.domain.SubscriptionPlan

/**
 * Подписки в памяти (issue #103): экран проверяется без MockWebServer.
 *
 * Ответ `current()` задаётся списком: после оформления и отмены подписка
 * перечитывается, и «до» от «после» иначе не отличить.
 */
class FakeSubscriptionRepository : SubscriptionRepository {

    var plans: ApiResult<List<SubscriptionPlan>> = ApiResult.Success(emptyList())

    /** Что вернуть на очередной вызов `current()`; кончились — берётся последний. */
    var currentAnswers: MutableList<ApiResult<Subscription?>> =
        mutableListOf(ApiResult.Success(null))

    var subscribeResult: ApiResult<Subscription?> = ApiResult.Success(null)
    var trialResult: ApiResult<Subscription?> = ApiResult.Success(null)
    var cancelResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var autoRenewResult: ApiResult<Unit> = ApiResult.Success(Unit)

    val requestedAudiences = mutableListOf<PlanAudience>()
    val subscribeRequests = mutableListOf<Pair<String, BillingPeriod>>()
    val trialRequests = mutableListOf<String>()
    var cancelCount: Int = 0
        private set
    val autoRenewRequests = mutableListOf<Boolean>()
    var currentCount: Int = 0
        private set

    override suspend fun plans(audience: PlanAudience): ApiResult<List<SubscriptionPlan>> {
        requestedAudiences += audience
        return plans
    }

    override suspend fun current(): ApiResult<Subscription?> {
        currentCount++
        return if (currentAnswers.size > 1) currentAnswers.removeAt(0) else currentAnswers.first()
    }

    override suspend fun subscribe(
        plan: SubscriptionPlan,
        period: BillingPeriod,
    ): ApiResult<Subscription?> {
        subscribeRequests += plan.code to period
        return subscribeResult
    }

    override suspend fun startTrial(plan: SubscriptionPlan): ApiResult<Subscription?> {
        trialRequests += plan.code
        return trialResult
    }

    override suspend fun cancel(): ApiResult<Unit> {
        cancelCount++
        return cancelResult
    }

    override suspend fun setAutoRenew(enabled: Boolean): ApiResult<Unit> {
        autoRenewRequests += enabled
        return autoRenewResult
    }
}
