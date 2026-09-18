package uz.mahalla.testutil

import kotlinx.coroutines.CompletableDeferred
import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.subscription.data.SubscriptionRepository
import uz.mahalla.feature.subscription.domain.BillingPeriod
import uz.mahalla.feature.subscription.domain.PlanAudience
import uz.mahalla.feature.subscription.domain.Subscription
import uz.mahalla.feature.subscription.domain.SubscriptionChargePage
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

    /**
     * Что вернуть на очередной вызов `charges()`; кончились — берётся
     * последний. Списком по той же причине, что и у `current()`: догрузка
     * страницы и перечит после оформления от первой выдачи отличаются только
     * порядком.
     */
    var chargeAnswers: MutableList<ApiResult<SubscriptionChargePage>> =
        mutableListOf(ApiResult.Success(SubscriptionChargePage()))

    /**
     * Задержка ответа истории: пока `gate` не завершён, `charges()` висит. Так
     * проверяются гонки — например «показать ещё» поверх идущей перезагрузки.
     */
    var chargeGate: CompletableDeferred<Unit>? = null

    val requestedAudiences = mutableListOf<PlanAudience>()
    val subscribeRequests = mutableListOf<Pair<String, BillingPeriod>>()
    val trialRequests = mutableListOf<String>()
    var cancelCount: Int = 0
        private set
    val autoRenewRequests = mutableListOf<Boolean>()
    var currentCount: Int = 0
        private set

    /** Номера страниц, с которых запрашивалась история списаний. */
    val chargeRequests = mutableListOf<Int>()

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

    override suspend fun charges(fromPage: Int, size: Int): ApiResult<SubscriptionChargePage> {
        chargeRequests += fromPage
        // Запрос повисает, пока тест не отпустит: иначе ответ приезжает раньше,
        // чем тест успевает нажать «показать ещё» поверх идущей загрузки.
        chargeGate?.await()
        return if (chargeAnswers.size > 1) chargeAnswers.removeAt(0) else chargeAnswers.first()
    }
}
