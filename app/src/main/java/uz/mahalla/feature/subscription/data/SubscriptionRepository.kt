package uz.mahalla.feature.subscription.data

import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.map
import uz.mahalla.data.network.ensureSuccess
import uz.mahalla.data.network.payload
import uz.mahalla.feature.subscription.domain.BillingPeriod
import uz.mahalla.feature.subscription.domain.PlanAudience
import uz.mahalla.feature.subscription.domain.Subscription
import uz.mahalla.feature.subscription.domain.SubscriptionPlan
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Тарифы и подписка (issue #103, эпик #13).
 *
 * Кэша нет намеренно: срок подписки, автопродление и состав тарифов меняются
 * на сервере (в том числе списанием по автопродлению, о котором приложение не
 * узнает), и устаревший «активна до» из Room был бы прямой ложью про деньги.
 *
 * Интерфейс — ради тестов ViewModel: экран проверяется без MockWebServer.
 */
interface SubscriptionRepository {

    suspend fun plans(audience: PlanAudience = PlanAudience.User): ApiResult<List<SubscriptionPlan>>

    /**
     * Текущая подписка. `null` — её нет, и это **не** ошибка: у большинства
     * пользователей подписки не будет никогда, а экран тарифов открывают как
     * раз из этого состояния.
     */
    suspend fun current(): ApiResult<Subscription?>

    /**
     * Оформление. Ручка выбирается по [SubscriptionPlan.audience]: у
     * бизнес-тарифов своя.
     *
     * Возвращает подписку из ответа сервера; `null` — сервер подтвердил
     * оформление, но подписку не назвал (тогда её перечитывает экран).
     */
    suspend fun subscribe(plan: SubscriptionPlan, period: BillingPeriod): ApiResult<Subscription?>

    /** Пробный период. Тариф без пробного периода в сеть не уходит. */
    suspend fun startTrial(plan: SubscriptionPlan): ApiResult<Subscription?>

    suspend fun cancel(): ApiResult<Unit>

    suspend fun setAutoRenew(enabled: Boolean): ApiResult<Unit>

    companion object {
        /** Код отказа, когда у тарифа нет пробного периода. */
        const val NO_TRIAL_CODE = "SUBSCRIPTION_NO_TRIAL"
    }
}

@Singleton
class DefaultSubscriptionRepository @Inject constructor(
    private val api: SubscriptionsApi,
) : SubscriptionRepository {

    override suspend fun plans(audience: PlanAudience): ApiResult<List<SubscriptionPlan>> =
        apiCall { api.plans(audience = audience.queryValue()).payload() }
            .map { plans -> plans.mapNotNull(PlanDto::toDomain) }

    /**
     * `ensureSuccess()`, а не `payload()`: «подписки нет» приезжает как
     * успешный конверт с пустым `data`, и `payload()` превратил бы штатный
     * ответ в ошибку разбора.
     *
     * Отсутствие подписки бэкенд может сообщить и отказом — `404` либо
     * бизнес-код, кончающийся на `NOT_FOUND`. Под токеном это не проверить
     * (SMS-кода в CI нет), поэтому принимаются оба вида: у ручки нет ни
     * параметров пути, ни тела, и «не найдено» здесь может относиться только к
     * самой подписке. Показывать «технический сбой» человеку, у которого
     * подписки просто нет, — худший из вариантов.
     */
    override suspend fun current(): ApiResult<Subscription?> {
        val result = apiCall {
            val response = api.current()
            response.ensureSuccess()
            response.data?.toDomain()
        }
        return if (result is ApiResult.Failure && result.error.isNotFound()) {
            ApiResult.Success(null)
        } else {
            result
        }
    }

    override suspend fun subscribe(
        plan: SubscriptionPlan,
        period: BillingPeriod,
    ): ApiResult<Subscription?> {
        val request = SubscribeRequest(
            planCode = plan.code,
            billingPeriod = period.apiValue,
        )
        return apiCall {
            val response = if (plan.audience == PlanAudience.Business) {
                api.subscribeBusiness(request)
            } else {
                api.subscribe(request)
            }
            response.ensureSuccess()
            response.data?.toDomain()
        }
    }

    /**
     * Тариф без пробного периода в сеть не уходит: сервер ответил бы тем же
     * отказом, но платой были бы запрос и молчание экрана на время его
     * выполнения (то же правило, что у отзыва в issue #76).
     */
    override suspend fun startTrial(plan: SubscriptionPlan): ApiResult<Subscription?> {
        if (!plan.hasTrial) {
            return ApiResult.Failure(ApiError.Business(SubscriptionRepository.NO_TRIAL_CODE))
        }
        return apiCall {
            val response = api.trial(planCode = plan.code)
            response.ensureSuccess()
            response.data?.toDomain()
        }
    }

    /**
     * Отмена. Причина не отправляется: у сервера для неё есть значение по
     * умолчанию («пользователь отменил»), а спрашивать её у человека экран не
     * спрашивает — придумывать за него текст, который увидит поддержка,
     * нельзя.
     */
    override suspend fun cancel(): ApiResult<Unit> =
        apiCall { api.cancel(reason = null).ensureSuccess() }

    override suspend fun setAutoRenew(enabled: Boolean): ApiResult<Unit> =
        apiCall { api.autoRenew(ToggleAutoRenewRequest(autoRenew = enabled)).ensureSuccess() }
}

/**
 * Незнакомую аудиторию сервер разбирать не обязан — спрашиваем `USER`, то есть
 * ровно то, что он берёт по умолчанию.
 */
private fun PlanAudience.queryValue(): String =
    apiValue.takeIf { it.isNotEmpty() } ?: PlanAudience.User.apiValue

private fun ApiError.isNotFound(): Boolean = when (this) {
    ApiError.NotFound -> true
    is ApiError.Business -> code?.trim()?.uppercase()?.endsWith("NOT_FOUND") == true
    else -> false
}
