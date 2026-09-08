package uz.mahalla.feature.wallet.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.data.network.NetworkFactory
import uz.mahalla.feature.wallet.domain.TopUpProvider
import uz.mahalla.feature.wallet.domain.TransactionDirection
import uz.mahalla.feature.wallet.domain.TransactionStatus
import uz.mahalla.feature.wallet.domain.WalletAmounts
import uz.mahalla.feature.wallet.domain.WalletStatus
import uz.mahalla.feature.wallet.domain.WalletTransaction
import java.time.Instant

/**
 * Кошелёк (issue #62) на настоящем сетевом стеке ([NetworkFactory] +
 * [MockWebServer]): подмена Retrofit фейком не поймала бы ни ошибку в пути
 * запроса, ни несовпадение схемы JSON.
 *
 * Контракт снят со стенда: `GET wallet` → `WalletResponse`,
 * `GET wallet/transactions?page&size` → страница `TransactionResponse`, оба
 * ответа — в общем конверте.
 */
class WalletRepositoryTest {

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
    fun `balance is read in sums, not in the minor unit of the server`() = runTest {
        server.enqueue(
            envelope(
                """{"id":"w-1","balance":128450000,"bonusBalance":1500000,"heldAmount":8450000,
                   "availableBalance":120000000,"currency":"UZS","status":"ACTIVE",
                   "balanceSom":1284500.0,"bonusBalanceSom":15000.0}""",
            ),
        )

        val wallet = (repository().wallet() as ApiResult.Success).data

        assertEquals("/wallet", server.takeRequest().path)
        assertEquals(1_284_500L, wallet.balanceSum)
        assertEquals(15_000L, wallet.bonusSum)
        // Заморозка и «доступно» дробного близнеца не имеют — они пересчитаны
        // тем же делителем, иначе внутри одной карточки суммы разъехались бы
        // в сто раз.
        assertEquals(84_500L, wallet.heldSum)
        assertEquals(1_200_000L, wallet.availableSum)
        assertEquals("UZS", wallet.currency)
        assertEquals(WalletStatus.Active, wallet.status)
    }

    @Test
    fun `available balance is computed when the server omits it`() = runTest {
        server.enqueue(
            envelope("""{"balance":100000000,"heldAmount":25000000,"balanceSom":1000000.0}"""),
        )

        val wallet = (repository().wallet() as ApiResult.Success).data

        assertEquals(750_000L, wallet.availableSum)
    }

    @Test
    fun `a negative balance from the server is clamped to zero`() = runTest {
        // Отрицательный баланс — ошибка сервера; показывать «−5 000» человеку
        // незачем, а на проверку «хватает ли денег» он влияет как ноль.
        server.enqueue(envelope("""{"balance":-500000,"balanceSom":-5000.0}"""))

        val wallet = (repository().wallet() as ApiResult.Success).data

        assertEquals(0L, wallet.balanceSum)
        assertEquals(0L, wallet.availableSum)
    }

    @Test
    fun `history asks for a page and maps the envelope`() = runTest {
        server.enqueue(
            envelope(
                """{"content":[
                     {"id":"t-1","type":"TOP_UP","direction":"IN","amount":50000000,
                      "amountSom":500000.0,"isBonus":false,"balanceAfter":128450000,
                      "description":"Payme","status":"COMPLETED",
                      "createdAt":"2026-08-29T12:30:00Z"},
                     {"id":"t-2","type":"ORDER_PAYMENT","direction":"OUT","amount":8450000,
                      "amountSom":84500.0,"isBonus":true,"status":"PENDING",
                      "createdAt":"2026-08-30T07:05:00"}
                   ],"page":0,"size":20,"totalElements":42,"totalPages":3,"last":false}""",
            ),
        )

        val page = (repository().transactions(page = 0) as ApiResult.Success).data

        assertEquals("/wallet/transactions?page=0&size=20", server.takeRequest().path)
        assertEquals(listOf("t-1", "t-2"), page.items.map(WalletTransaction::id))
        val topUp = page.items.first()
        assertEquals(TransactionDirection.In, topUp.direction)
        assertEquals(500_000L, topUp.signedAmountSum)
        assertEquals(1_284_500L, topUp.balanceAfterSum)
        assertEquals(TransactionStatus.Completed, topUp.status)
        assertEquals(Instant.parse("2026-08-29T12:30:00Z"), topUp.createdAt)

        val payment = page.items.last()
        // Списание показывается со знаком независимо от того, прислал ли его
        // сервер: «+84 500» за оплаченный заказ читалось бы как пополнение.
        assertEquals(-84_500L, payment.signedAmountSum)
        assertEquals(84_500L, payment.amountSum)
        assertTrue(payment.isBonus)
        assertEquals(TransactionStatus.Pending, payment.status)
        // Jackson отдаёт `LocalDateTime` без зоны — иначе дата пуста у всех.
        assertEquals(Instant.parse("2026-08-30T07:05:00Z"), payment.createdAt)
        assertTrue(page.hasMore)
    }

    @Test
    fun `a debit sent with a minus keeps its sign once`() = runTest {
        server.enqueue(
            envelope(
                """{"content":[{"id":"t-3","direction":"DEBIT","amount":-8450000,
                   "amountSom":-84500.0}],"last":true}""",
            ),
        )

        val page = (repository().transactions() as ApiResult.Success).data

        assertEquals(-84_500L, page.items.single().signedAmountSum)
        assertFalse(page.hasMore)
    }

    @Test
    fun `an entry without an id is dropped instead of breaking the list`() = runTest {
        server.enqueue(
            envelope("""{"content":[{"type":"BONUS"},{"id":"t-4"}],"page":1,"totalPages":3}"""),
        )

        val page = (repository().transactions(page = 1) as ApiResult.Success).data

        assertEquals(listOf("t-4"), page.items.map(WalletTransaction::id))
        // `last` не приехал — «есть ли ещё» считается по номеру страницы.
        assertTrue(page.hasMore)
    }

    @Test
    fun `silence about paging stops the load more loop`() = runTest {
        server.enqueue(envelope("""{"content":[{"id":"t-5"}]}"""))

        val page = (repository().transactions() as ApiResult.Success).data

        // Иначе экран догружал бы одну и ту же страницу до бесконечности.
        assertFalse(page.hasMore)
    }

    @Test
    fun `success false is a failure, not an empty wallet`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"WALLET_NOT_FOUND",
                       "message":"Hamyon topilmadi"}}""",
                ),
        )

        val failure = (repository().wallet() as ApiResult.Failure).failure

        assertEquals(ApiError.Business("WALLET_NOT_FOUND"), failure.error)
        assertEquals("Hamyon topilmadi", failure.serverMessage)
    }

    @Test
    fun `expired token is reported as unauthorized`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        assertEquals(
            ApiError.Unauthorized,
            (repository().wallet() as ApiResult.Failure).error,
        )
    }

    // --- Пополнение (issue #93) ---

    /**
     * Тело запроса — то, ради чего этот тест существует: `amount` уходит в
     * единицах бэкенда, а не в сумах, а провайдер — значением его же
     * перечисления.
     */
    @Test
    fun `top up sends the amount in the units of the backend`() = runTest {
        server.enqueue(
            envelope(
                """{"paymentUrl":"https://checkout.paycom.uz/abc","transactionId":"p-1",
                   "amount":25000000,"provider":"PAYME","expiresAt":"2026-09-04T10:00:00Z"}""",
            ),
        )

        val order = (
            repository().topUp(
                amountSum = 250_000,
                provider = TopUpProvider.Payme,
                scale = WalletAmounts.TIYIN_IN_SOM,
            ) as ApiResult.Success
            ).data

        val request = server.takeRequest()
        assertEquals("/wallet/top-up", request.path)
        assertEquals("POST", request.method)
        assertEquals(
            """{"amount":25000000,"provider":"PAYME"}""",
            request.body.readUtf8(),
        )
        assertEquals("https://checkout.paycom.uz/abc", order.paymentUrl)
    }

    /** Тот же ввод при другом делителе уходит другим числом. */
    @Test
    fun `top up in a wallet counted in sums sends the sum as is`() = runTest {
        server.enqueue(envelope("""{"paymentUrl":"https://my.click.uz/pay?id=7"}"""))

        repository().topUp(amountSum = 250_000, provider = TopUpProvider.Click, scale = 1L)

        assertEquals(
            """{"amount":250000,"provider":"CLICK"}""",
            server.takeRequest().body.readUtf8(),
        )
    }

    /**
     * Сумма ниже серверного минимума в сеть не уходит: 400 сказал бы то же
     * самое, но платой были бы запрос и молчание экрана.
     */
    @Test
    fun `amount below the minimum does not reach the network`() = runTest {
        val failure = repository().topUp(
            amountSum = 999,
            provider = TopUpProvider.Payme,
            scale = WalletAmounts.TIYIN_IN_SOM,
        ) as ApiResult.Failure

        assertEquals(
            ApiError.Business(WalletRepository.INVALID_TOP_UP_CODE),
            failure.error,
        )
        assertEquals(0, server.requestCount)
    }

    /**
     * Ответ без годной ссылки — отказ: платить негде, и «платёж заведён» без
     * формы было бы неправдой. `http` и чужие схемы отсекаются здесь же —
     * ссылку присылает сервер, а его адрес в debug вводит пользователь.
     */
    @Test
    fun `response without a usable payment form is a failure`() = runTest {
        server.enqueue(envelope("""{"transactionId":"p-2","amount":25000000}"""))
        server.enqueue(envelope("""{"paymentUrl":"http://checkout.paycom.uz/abc"}"""))
        server.enqueue(envelope("""{"paymentUrl":"mahalla://place/1"}"""))
        val repository = repository()

        repeat(3) {
            val failure = repository.topUp(
                amountSum = 250_000,
                provider = TopUpProvider.Payme,
                scale = WalletAmounts.TIYIN_IN_SOM,
            ) as ApiResult.Failure
            assertEquals(
                ApiError.Business(WalletRepository.NO_PAYMENT_URL_CODE),
                failure.error,
            )
        }
    }

    /** Провайдер отказал — текст сервера обязан доехать до шторки (issue #34). */
    @Test
    fun `provider failure is reported with the message of the server`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(502)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"PROVIDER_UNAVAILABLE",
                       "message":"Payme javob bermadi"}}""",
                ),
        )

        val failure = (
            repository().topUp(
                amountSum = 250_000,
                provider = TopUpProvider.Payme,
                scale = WalletAmounts.TIYIN_IN_SOM,
            ) as ApiResult.Failure
            ).failure

        assertEquals("Payme javob bermadi", failure.serverMessage)
        assertEquals("PROVIDER_UNAVAILABLE", failure.server?.code)
    }

    @Test
    fun `top up answered with success false is a failure`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
                .setBody(
                    """{"success":false,"error":{"code":"WALLET_BLOCKED",
                       "message":"Hamyon bloklangan"}}""",
                ),
        )

        val failure = (
            repository().topUp(
                amountSum = 250_000,
                provider = TopUpProvider.Uzum,
                scale = WalletAmounts.TIYIN_IN_SOM,
            ) as ApiResult.Failure
            ).failure

        assertEquals(ApiError.Business("WALLET_BLOCKED"), failure.error)
        assertEquals("Hamyon bloklangan", failure.serverMessage)
    }

    @Test
    fun `top up without a session is reported as unauthorized`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        assertEquals(
            ApiError.Unauthorized,
            (
                repository().topUp(
                    amountSum = 250_000,
                    provider = TopUpProvider.Payme,
                    scale = WalletAmounts.TIYIN_IN_SOM,
                ) as ApiResult.Failure
                ).error,
        )
    }

    /**
     * Делитель приезжает в домене вместе с балансом: пополнение обязано
     * считать сумму тем же делителем, которым посчитан показанный баланс.
     */
    @Test
    fun `scale of the response reaches the domain`() = runTest {
        server.enqueue(envelope("""{"balance":128450000,"balanceSom":1284500.0}"""))

        val wallet = (repository().wallet() as ApiResult.Success).data

        assertEquals(WalletAmounts.TIYIN_IN_SOM, wallet.amountScale)
    }

    private fun repository() = DefaultWalletRepository(
        NetworkFactory
            .retrofit(
                server.url("/").toString(),
                NetworkFactory.clientBuilder().build(),
                NetworkFactory.converterFactory(NetworkFactory.json()),
            )
            .create(WalletApi::class.java),
    )

    private fun envelope(data: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", NetworkFactory.CONTENT_TYPE)
        .setBody("""{"success":true,"data":$data}""")
}
