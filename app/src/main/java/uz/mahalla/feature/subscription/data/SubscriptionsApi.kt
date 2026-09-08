package uz.mahalla.feature.subscription.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query
import uz.mahalla.data.network.ApiResponse

/**
 * Подписки (контроллер `subscription`, issue #103, эпик #13).
 *
 * Контракт снят со стенда (`/v3/api-docs` + curl 2026-09-04): **все** ручки
 * требуют Bearer — без токена приходит `401 UNAUTHORIZED`, включая список
 * тарифов, — поэтому API создаётся на **основном** Retrofit, а не на «голом»
 * `@RefreshClient`. Ответы приезжают в общем конверте `{success, data, error}`.
 *
 * Гео-заголовки обязательны и здесь (`403 GEO_PERMISSION_REQUIRED` без них,
 * проверено), но их уже ставит `GeoHeaderInterceptor` на обоих клиентах
 * (issue #53).
 *
 * Две ручки принимают параметры **query**, а не телом, и это видно только в
 * схеме: у `trial` там `planCode`, у `cancel` — `reason`. Тела у них нет
 * вовсе.
 *
 * `GET payments/subscription` намеренно не используется: он отдаёт сырую
 * сущность `Subscription` (`plan` перечислением `FREE|BASIC|PRO|PREMIUM`, без
 * `daysRemaining`, `isTrial` и грейс-периода), то есть строго меньше, чем
 * `subscriptions/current`, и о том же самом.
 */
interface SubscriptionsApi {

    /** Тарифы. `audience` — `USER` или `BUSINESS`; сервер по умолчанию берёт `USER`. */
    @GET("subscriptions/plans")
    suspend fun plans(@Query("audience") audience: String): ApiResponse<List<PlanDto>>

    @GET("subscriptions/current")
    suspend fun current(): ApiResponse<SubscriptionDto>

    @POST("subscriptions/subscribe")
    suspend fun subscribe(@Body request: SubscribeRequest): ApiResponse<SubscriptionDto>

    /** У бизнес-тарифов своя ручка — тело то же самое. */
    @POST("subscriptions/business/subscribe")
    suspend fun subscribeBusiness(@Body request: SubscribeRequest): ApiResponse<SubscriptionDto>

    @POST("subscriptions/trial")
    suspend fun trial(@Query("planCode") planCode: String): ApiResponse<SubscriptionDto>

    /**
     * Отмена. Тела нет, причина — query-параметр; у сервера для него есть
     * значение по умолчанию, поэтому приложение шлёт свою причину, только если
     * ей есть чем отличаться от «пользователь отменил».
     */
    @POST("subscriptions/cancel")
    suspend fun cancel(@Query("reason") reason: String?): ApiResponse<JsonElement>

    @PUT("subscriptions/auto-renew")
    suspend fun autoRenew(@Body request: ToggleAutoRenewRequest): ApiResponse<JsonElement>
}

/**
 * `SubscribeRequest`. Значений по умолчанию у полей нет намеренно:
 * kotlinx.serialization выбрасывает из тела поля, равные дефолту, и бэкенд
 * получал бы запрос без периода оплаты (та же грабля, что у `revokeAll` в
 * issue #61).
 */
@Serializable
data class SubscribeRequest(
    @SerialName("planCode") val planCode: String,
    @SerialName("billingPeriod") val billingPeriod: String,
)

/** `ToggleAutoRenewRequest` — одно обязательное поле, дефолта тоже нет. */
@Serializable
data class ToggleAutoRenewRequest(
    @SerialName("autoRenew") val autoRenew: Boolean,
)

/**
 * `PlanResponse`. Все поля необязательные, как везде в этом API: отсутствие
 * любого из них — не повод показать экран ошибки вместо списка тарифов.
 *
 * Цены приезжают парами (`monthlyPrice` + `monthlyPriceSom`) — по ним
 * определяется единица целых полей
 * ([uz.mahalla.feature.subscription.domain.SubscriptionAmounts]).
 */
@Serializable
data class PlanDto(
    @SerialName("id") val id: String? = null,
    @SerialName("code") val code: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("nameUz") val nameUz: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("audience") val audience: String? = null,
    @SerialName("tier") val tier: String? = null,
    @SerialName("monthlyPrice") val monthlyPrice: Long? = null,
    @SerialName("yearlyPrice") val yearlyPrice: Long? = null,
    @SerialName("monthlyPriceSom") val monthlyPriceSom: Double? = null,
    @SerialName("yearlyPriceSom") val yearlyPriceSom: Double? = null,
    @SerialName("yearlyDiscountPercent") val yearlyDiscountPercent: Int? = null,
    @SerialName("maxPlaces") val maxPlaces: Int? = null,
    @SerialName("maxListings") val maxListings: Int? = null,
    @SerialName("maxPhotosPerListing") val maxPhotosPerListing: Int? = null,
    @SerialName("freePromotionsMonthly") val freePromotionsMonthly: Int? = null,
    @SerialName("analyticsLevel") val analyticsLevel: String? = null,
    @SerialName("hasPrioritySupport") val hasPrioritySupport: Boolean? = null,
    @SerialName("hasVerifiedBadge") val hasVerifiedBadge: Boolean? = null,
    @SerialName("hasFeaturedListing") val hasFeaturedListing: Boolean? = null,
    @SerialName("hasCustomBranding") val hasCustomBranding: Boolean? = null,
    @SerialName("hasApiAccess") val hasApiAccess: Boolean? = null,
    @SerialName("hasMultiStaff") val hasMultiStaff: Boolean? = null,
    @SerialName("noAds") val noAds: Boolean? = null,
    @SerialName("isPopular") val isPopular: Boolean? = null,
    /** Jackson сериализует `boolean isX` то `isX`, то `x` — принимаем оба. */
    @SerialName("popular") val popular: Boolean? = null,
    @SerialName("trialDays") val trialDays: Int? = null,
    @SerialName("isFree") val isFree: Boolean? = null,
    @SerialName("free") val free: Boolean? = null,
)

/** `SubscriptionResponse`. */
@Serializable
data class SubscriptionDto(
    @SerialName("id") val id: String? = null,
    @SerialName("planCode") val planCode: String? = null,
    @SerialName("planName") val planName: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("billingPeriod") val billingPeriod: String? = null,
    @SerialName("pricePaid") val pricePaid: Long? = null,
    @SerialName("pricePaidSom") val pricePaidSom: Double? = null,
    @SerialName("startedAt") val startedAt: String? = null,
    @SerialName("expiresAt") val expiresAt: String? = null,
    @SerialName("autoRenew") val autoRenew: Boolean? = null,
    @SerialName("isTrial") val isTrial: Boolean? = null,
    @SerialName("trial") val trial: Boolean? = null,
    @SerialName("daysRemaining") val daysRemaining: Long? = null,
    @SerialName("isActive") val isActive: Boolean? = null,
    @SerialName("active") val active: Boolean? = null,
    @SerialName("inGracePeriod") val inGracePeriod: Boolean? = null,
)
