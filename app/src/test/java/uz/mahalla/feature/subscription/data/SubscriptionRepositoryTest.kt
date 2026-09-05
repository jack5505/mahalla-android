package uz.mahalla.feature.subscription.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.feature.subscription.domain.BillingPeriod
import uz.mahalla.feature.subscription.domain.PlanAudience
import uz.mahalla.feature.subscription.domain.PlanFeature
import uz.mahalla.feature.subscription.domain.SubscriptionPlan
import uz.mahalla.feature.subscription.domain.SubscriptionStatus
import java.time.Instant

/**
 * Подписки (issue #103) на настоящем сетевом стеке ([NetworkFactory] +
 * [MockWebServer]): подмена Retrofit фейком не поймала бы ни ошибку в пути
 * запроса, ни несовпадение схемы JSON.
 *
 * Контракт снят со стенда 2026-09-04: `GET subscriptions/plans?audience`,
 * `GET subscriptions/current`, `POST subscriptions/subscribe` (и
 * `business/subscribe`), `POST subscriptions/trial?planCode`,
 * `POST subscriptions/cancel?reason`, `PUT subscriptions/auto-renew` — всё в
 * общем конверте и всё под Bearer.
 */
class SubscriptionRepositoryTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `plans are asked for the audience and read in sums`() = runTest {
        server.enqueue(
            envelope(
                """[{"id":"p-1","code":"PRO","name":"Профи","nameUz":"Pro",
                     "description":"Reklamasiz","audience":"USER","tier":"PRO",
                     "monthlyPrice":4900000,"yearlyPrice":47000000,
                     "monthlyPriceSom":49000.0,"yearlyPriceSom":470000.0,
                     "yearlyDiscountPercent":20,"maxPlaces":3,"maxListings":0,
                     "analyticsLevel":"BASIC","noAds":true,"hasVerifiedBadge":true,
                     "hasApiAccess":false,"isPopular":true,"trialDays":7,"isFree":false}]""",
            ),
        )

        val plans = (repository().plans() as ApiResult.Success).data

        assertEquals("/subscriptions/plans?audience=USER", server.takeRequest().path)
        val plan = plans.single()
        assertEquals("PRO", plan.code)
        assertEquals("Pro", plan.displayName(uzbek = true))
        // Цены пересчитаны делителем, который вывела пара `monthlyPrice` и
        // `monthlyPriceSom`, — как в кошельке (issue #62).
        assertEquals(49_000L, plan.monthlySum)
        assertEquals(470_000L, plan.yearlySum)
        assertEquals(20, plan.savingsPercent)
        assertEquals(setOf(PlanFeature.NoAds, PlanFeature.VerifiedBadge), plan.features)
        assertEquals(3, plan.maxPlaces)
        // Ноль лимитом не считается: «объявлений: 0» — это не возможность.
        assertNull(plan.maxListings)
        assertEquals("BASIC", plan.analyticsLevel)
        assertTrue(plan.isPopular)
        assertTrue(plan.hasTrial)
    }

    @Test
    fun `business plans are asked from the same endpoint with another audience`() = runTest {
        server.enqueue(envelope("[]"))

        val plans = (repository().plans(PlanAudience.Business) as ApiResult.Success).data

        assertEquals("/subscriptions/plans?audience=BUSINESS", server.takeRequest().path)
        assertTrue(plans.isEmpty())
    }

    @Test
    fun `a plan without a code is dropped, the rest of the list survives`() = runTest {
        // Оформить такой тариф нечем (`planCode` обязателен), а в списке он ещё
        // и дубликат ключа.
        server.enqueue(
            envelope("""[{"name":"Без кода"},{"code":"FREE","isFree":true}]"""),
        )

        val plans = (repository().plans() as ApiResult.Success).data

        assertEquals(listOf("FREE"), plans.map(SubscriptionPlan::code))
    }

    @Test
    fun `both spellings of the boolean flags are accepted`() = runTest {
        // Jackson сериализует `boolean isPopular` то `isPopular`, то `popular`.
        server.enqueue(envelope("""[{"code":"PRO","popular":true,"free":true}]"""))

        val plan = (repository().plans() as ApiResult.Success).data.single()

        assertTrue(plan.isPopular)
        assertTrue(plan.isFree)
    }

    @Test
    fun `the current subscription is read from the envelope`() = runTest {
        server.enqueue(
            envelope(
                """{"id":"s-1","planCode":"PRO","planName":"Pro","status":"ACTIVE",
                   "billingPeriod":"MONTHLY","pricePaid":4900000,"pricePaidSom":49000.0,
                   "startedAt":"2026-09-04T09:00:00Z","expiresAt":"2026-10-04T09:00:00",
                   "autoRenew":true,"isTrial":false,"daysRemaining":30,"isActive":true,
                   "inGracePeriod":false}""",
            ),
        )

        val subscription = (repository().current() as ApiResult.Success).data!!

        assertEquals("/subscriptions/current", server.takeRequest().path)
        assertEquals("PRO", subscription.planCode)
        assertEquals(SubscriptionStatus.Active, subscription.status)
        assertEquals(BillingPeriod.Monthly, subscription.billingPeriod)
        assertEquals(49_000L, subscription.pricePaidSum)
        assertEquals(Instant.parse("2026-09-04T09:00:00Z"), subscription.startedAt)
        // Jackson отдаёт `LocalDateTime` без зоны — общий разбор это умеет и
        // читает такое время как UTC.
        assertEquals(Instant.parse("2026-10-04T09:00:00Z"), subscription.expiresAt)
        assertTrue(subscription.autoRenew)
        assertEquals(30L, subscription.daysRemaining)
        assertTrue(subscription.isActive)
    }

    @Test
    fun `an empty payload means there is no subscription, not a broken answer`() = runTest {
        // У большинства подписки не будет никогда, и это штатный ответ.
        server.enqueue(envelope("null"))

        assertNull((repository().current() as ApiResult.Success).data)
    }

    @Test
    fun `a not found answer also means there is no subscription`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody("""{"success":false,"error":{"code":"NOT_FOUND","message":"Obuna yo'q"}}"""),
        )

        assertNull((repository().current() as ApiResult.Success).data)
    }

    @Test
    fun `a business not found code is treated the same way`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,
                       "error":{"code":"SUBSCRIPTION_NOT_FOUND","message":"Obuna topilmadi"}}""",
                ),
        )

        assertNull((repository().current() as ApiResult.Success).data)
    }

    @Test
    fun `other refusals of the current subscription stay refusals`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        assertEquals(
            ApiError.Unauthorized,
            (repository().current() as ApiResult.Failure).error,
        )
    }

    @Test
    fun `subscribing sends the plan code and the billing period`() = runTest {
        server.enqueue(envelope("""{"planCode":"PRO","status":"ACTIVE","isActive":true}"""))

        val result = repository().subscribe(plan(), BillingPeriod.Yearly)

        val request = server.takeRequest()
        assertEquals("/subscriptions/subscribe", request.path)
        assertEquals("POST", request.method)
        assertEquals(
            """{"planCode":"PRO","billingPeriod":"YEARLY"}""",
            request.body.readUtf8(),
        )
        assertEquals("PRO", (result as ApiResult.Success).data?.planCode)
    }

    @Test
    fun `a business plan is subscribed through its own endpoint`() = runTest {
        server.enqueue(envelope("""{"planCode":"BIZ","status":"ACTIVE"}"""))

        repository().subscribe(plan(code = "BIZ", audience = PlanAudience.Business), BillingPeriod.Monthly)

        assertEquals("/subscriptions/business/subscribe", server.takeRequest().path)
    }

    @Test
    fun `a confirmed subscription without a body is not a failure`() = runTest {
        // Оформление подтверждено конвертом; подписку экран перечитает сам —
        // объявлять удачную операцию неудачной из-за пустого `data` нельзя.
        server.enqueue(envelope("null"))

        val result = repository().subscribe(plan(), BillingPeriod.Monthly)

        assertNull((result as ApiResult.Success).data)
    }

    @Test
    fun `a refusal of subscribing carries the text of the server`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,
                       "error":{"code":"INSUFFICIENT_FUNDS","message":"Hamyonda mablag' yetarli emas"}}""",
                ),
        )

        val failure = (repository().subscribe(plan(), BillingPeriod.Monthly) as ApiResult.Failure)
            .failure

        assertEquals("Hamyonda mablag' yetarli emas", failure.serverMessage)
    }

    @Test
    fun `a success false envelope is a refusal too`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody("""{"success":false,"error":{"code":"PLAN_UNAVAILABLE"}}"""),
        )

        assertEquals(
            ApiError.Business("PLAN_UNAVAILABLE"),
            (repository().subscribe(plan(), BillingPeriod.Monthly) as ApiResult.Failure).error,
        )
    }

    @Test
    fun `the trial sends the plan code in the query`() = runTest {
        server.enqueue(envelope("""{"planCode":"PRO","status":"ACTIVE","isTrial":true}"""))

        val result = repository().startTrial(plan(trialDays = 7))

        val request = server.takeRequest()
        assertEquals("/subscriptions/trial?planCode=PRO", request.path)
        assertEquals("POST", request.method)
        assertTrue((result as ApiResult.Success).data?.isTrial == true)
    }

    @Test
    fun `a plan without a trial does not reach the network`() = runTest {
        val result = repository().startTrial(plan(trialDays = 0))

        assertEquals(
            ApiError.Business(SubscriptionRepository.NO_TRIAL_CODE),
            (result as ApiResult.Failure).error,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `cancelling goes without a body and without a reason`() = runTest {
        server.enqueue(envelope("null"))

        val result = repository().cancel()

        val request = server.takeRequest()
        assertEquals("/subscriptions/cancel", request.path)
        assertEquals("POST", request.method)
        assertEquals("", request.body.readUtf8())
        assertTrue(result is ApiResult.Success)
    }

    @Test
    fun `a refusal of cancelling carries the text of the server`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,
                       "error":{"code":"ALREADY_CANCELLED","message":"Obuna allaqachon bekor qilingan"}}""",
                ),
        )

        val failure = (repository().cancel() as ApiResult.Failure).failure

        // 409 — обычный HTTP-отказ; текст сервера при этом доезжает до экрана
        // (issue #34), а машинный код лежит в разобранном ответе.
        assertEquals(409, (failure.error as ApiError.Http).code)
        assertEquals("ALREADY_CANCELLED", failure.server?.code)
        assertEquals("Obuna allaqachon bekor qilingan", failure.serverMessage)
    }

    @Test
    fun `auto-renew is sent as a flag in the body`() = runTest {
        server.enqueue(envelope("null"))

        val result = repository().setAutoRenew(enabled = false)

        val request = server.takeRequest()
        assertEquals("/subscriptions/auto-renew", request.path)
        assertEquals("PUT", request.method)
        // Значения по умолчанию у поля нет намеренно: иначе `false` вылетел бы
        // из тела и бэкенд получил бы пустой запрос.
        assertEquals("""{"autoRenew":false}""", request.body.readUtf8())
        assertTrue(result is ApiResult.Success)
    }

    @Test
    fun `without a session everything is reported as unauthorized`() = runTest {
        // Фактический ответ стенда: `401` на все ручки контроллера, включая
        // список тарифов.
        server.enqueue(MockResponse().setResponseCode(401))

        assertEquals(ApiError.Unauthorized, (repository().plans() as ApiResult.Failure).error)
        assertFalse(server.takeRequest().path.isNullOrEmpty())
    }

    private fun plan(
        code: String = "PRO",
        audience: PlanAudience = PlanAudience.User,
        trialDays: Int = 0,
    ) = SubscriptionPlan(
        code = code,
        audience = audience,
        monthlySum = 49_000,
        yearlySum = 470_000,
        trialDays = trialDays,
    )

    private fun repository() = DefaultSubscriptionRepository(
        NetworkFactory
            .retrofit(
                server.url("/").toString(),
                NetworkFactory.clientBuilder().build(),
                NetworkFactory.converterFactory(NetworkFactory.json()),
            )
            .create(SubscriptionsApi::class.java),
    )

    private fun envelope(data: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody("""{"success":true,"data":$data}""")
}
