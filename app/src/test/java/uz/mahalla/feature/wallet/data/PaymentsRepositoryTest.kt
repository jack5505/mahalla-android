package uz.mahalla.feature.wallet.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.feature.subscription.data.PaymentsApi
import uz.mahalla.feature.subscription.domain.ChargeProvider
import uz.mahalla.feature.subscription.domain.ChargeStatus
import uz.mahalla.feature.wallet.domain.PaymentTransaction
import java.time.Instant

/**
 * Вкладка «Платежи» кошелька (issue #184) на настоящем сетевом стеке
 * ([NetworkFactory] + [MockWebServer]): подмена Retrofit фейком не поймала бы
 * ни ошибку в пути запроса, ни несовпадение схемы JSON.
 *
 * Своей ручки у вкладки нет — используется `GET payments/transactions`, тот
 * же контроллер, что и у истории списаний за подписку
 * (`SubscriptionRepositoryTest`), но без фильтра по назначению.
 */
class PaymentsRepositoryTest {

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
    fun `all payments are read regardless of purpose`() = runTest {
        server.enqueue(
            envelope(
                """{"content":[
                     {"id":"pay-1","createdAt":"2026-09-04T09:00:00","provider":"PAYME",
                      "amount":4900000,"status":"PAID","purpose":"SUBSCRIPTION"},
                     {"id":"pay-2","createdAt":"2026-09-03T09:00:00","provider":"CLICK",
                      "amount":1000000,"status":"PAID","purpose":"WALLET_TOP_UP"}
                   ],"page":0,"size":20,"totalPages":1,"last":true}""",
            ),
        )

        val page = (repository().transactions() as ApiResult.Success).data

        assertEquals("/payments/transactions?page=0&size=20", server.takeRequest().path)
        // В отличие от истории подписки, здесь нет фильтра по purpose — оба
        // платежа должны остаться.
        assertEquals(listOf("pay-1", "pay-2"), page.items.map(PaymentTransaction::id))
        val first = page.items.first()
        // `amount` — тийины, как все целые денежные поля контракта (issue #149):
        // 4 900 000 тийинов → 49 000 сум.
        assertEquals(49_000L, first.amountSum)
        assertEquals(ChargeStatus.Paid, first.status)
        assertEquals(ChargeProvider.Payme, first.provider)
        assertEquals(Instant.parse("2026-09-04T09:00:00Z"), first.createdAt)
        assertFalse(page.hasMore)
    }

    @Test
    fun `pagination follows the requested page, not the one the server echoes`() = runTest {
        // Сервер не вернул `page` (пришёл дефолтный `0`), хотя запрошена
        // страница 3 из 4: если бы `hasMore` считался по этому `0`, история
        // выглядела бы бесконечной вместо «дальше некуда».
        server.enqueue(
            envelope(
                """{"content":[{"id":"pay-9","amount":4900000,"status":"PAID"}],
                     "totalPages":4}""",
            ),
        )

        val page = (repository().transactions(page = 3) as ApiResult.Success).data

        assertEquals("/payments/transactions?page=3&size=20", server.takeRequest().path)
        assertEquals(listOf("pay-9"), page.items.map(PaymentTransaction::id))
        assertFalse(page.hasMore)
    }

    @Test
    fun `a refusal reason of a failed payment reaches the domain`() = runTest {
        server.enqueue(
            envelope(
                """{"content":[{"id":"pay-1","provider":"CLICK","amount":4900000,
                     "status":"FAILED","errorMessage":"Mablag' yetarli emas"}],"last":true}""",
            ),
        )

        val payment = (repository().transactions() as ApiResult.Success).data.items.single()

        assertEquals(ChargeStatus.Failed, payment.status)
        assertEquals("Mablag' yetarli emas", payment.errorMessage)
    }

    @Test
    fun `a payment without an id is dropped from the history`() = runTest {
        server.enqueue(
            envelope(
                """{"content":[{"amount":4900000,"status":"PAID"},
                     {"id":"pay-1","amount":4900000,"status":"PAID"}
                   ],"last":true}""",
            ),
        )

        val page = (repository().transactions() as ApiResult.Success).data

        assertEquals(listOf("pay-1"), page.items.map(PaymentTransaction::id))
    }

    @Test
    fun `a refusal of the history stays a refusal`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = repository().transactions()

        assertEquals(ApiError.Unauthorized, (result as ApiResult.Failure).error)
    }

    private fun repository(): DefaultPaymentsRepository {
        val retrofit = NetworkFactory.retrofit(
            server.url("/").toString(),
            NetworkFactory.clientBuilder().build(),
            NetworkFactory.converterFactory(NetworkFactory.json()),
        )
        return DefaultPaymentsRepository(api = retrofit.create(PaymentsApi::class.java))
    }

    private fun envelope(data: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody("""{"success":true,"data":$data}""")
}
