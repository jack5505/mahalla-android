package uz.mahalla.feature.wallet.ui.pay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.ServerError
import uz.mahalla.data.security.PaymentConfirmationMethod
import uz.mahalla.feature.wallet.domain.Wallet
import uz.mahalla.feature.wallet.domain.WalletPaymentRejection
import uz.mahalla.feature.wallet.domain.WalletStatus
import uz.mahalla.testutil.FakePaymentConfirmationPolicy
import uz.mahalla.testutil.FakePinStorage
import uz.mahalla.testutil.FakeWalletRepository

/**
 * Оплата из кошелька (задача 8.3 эпика #12): подтверждение, идемпотентность,
 * отказы.
 *
 * Запрос вертикали здесь — счётчик и очередь ответов: проверяется не создание
 * заказа, а то, сколько раз и с каким ключом его попросили создать.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WalletPaymentFlowTest {

    private val walletRepository = FakeWalletRepository(
        initial = Wallet(balanceSum = 100_000, availableSum = 100_000),
    )
    private val pinStorage = FakePinStorage(initialPin = PIN)
    private val policy = FakePaymentConfirmationPolicy(PaymentConfirmationMethod.Pin)

    /** Ключи всех попыток отправки — по ним видно и повтор, и второй платёж. */
    private val sentKeys = mutableListOf<String>()

    /** Ответы на попытки по порядку; кончились — отдаётся [defaultResponse]. */
    private val responses = ArrayDeque<ApiResult<String>>()
    private var defaultResponse: ApiResult<String> = ApiResult.Success("o-1")

    @Test
    fun `not enough money is reported before the request leaves`() = runTest {
        // Сервер отказал бы тем же, но платой были бы запрос, ввод PIN и
        // ожидание — ради разницы, которую видно сразу.
        walletRepository.wallet = ApiResult.Success(Wallet(availableSum = 30_000))
        val flow = flow()

        flow.start(amountSum = 84_000)

        assertEquals(
            WalletPaymentRejection.InsufficientFunds(missingSum = 54_000),
            flow.state.value?.rejection,
        )
        assertEquals(emptyList<String>(), sentKeys)
    }

    @Test
    fun `frozen wallet is not offered a payment it cannot make`() = runTest {
        walletRepository.wallet = ApiResult.Success(
            Wallet(availableSum = 1_000_000, status = WalletStatus.Blocked),
        )
        val flow = flow()

        flow.start(amountSum = 84_000)

        assertEquals(WalletPaymentRejection.WalletBlocked, flow.state.value?.rejection)
        assertEquals(emptyList<String>(), sentKeys)
    }

    @Test
    fun `an unknown balance does not block the payment`() = runTest {
        // Решающее слово за сервером: отказать из-за неотвеченного запроса
        // хуже, чем получить отказ от него.
        walletRepository.wallet = ApiResult.Failure(ApiError.Timeout)
        val flow = flow()

        flow.start(amountSum = 84_000)

        assertNull(flow.state.value?.availableSum)
        assertEquals(WalletPaymentStep.Confirm, flow.state.value?.step)
        assertNull(flow.state.value?.rejection)
    }

    @Test
    fun `the request leaves only after the pin is confirmed`() = runTest {
        val flow = flow()
        flow.start(amountSum = 84_000)

        assertEquals(emptyList<String>(), sentKeys)

        flow.pinChanged(PIN)

        assertEquals(1, sentKeys.size)
    }

    @Test
    fun `a wrong pin costs an attempt and sends nothing`() = runTest {
        val flow = flow()
        flow.start(amountSum = 84_000)

        flow.pinChanged("000000")

        assertEquals(2, flow.state.value?.attemptsLeft)
        assertTrue(flow.state.value?.pin?.isError == true)
        assertEquals("", flow.state.value?.pin?.code)
        assertEquals(emptyList<String>(), sentKeys)
    }

    @Test
    fun `three wrong pins cancel the payment`() = runTest {
        val flow = flow()
        flow.start(amountSum = 84_000)

        repeat(3) { flow.pinChanged("000000") }

        assertEquals(WalletPaymentRejection.ConfirmationFailed, flow.state.value?.rejection)
        assertEquals(emptyList<String>(), sentKeys)
    }

    @Test
    fun `insufficient funds reported by the server are named as such`() = runTest {
        // Клиент считал, что денег хватает (баланс мог устареть), а сервер
        // отказал: человеку надо предложить пополнить, а не «повторить».
        defaultResponse = ApiResult.Failure(
            ApiFailure(
                error = ApiError.Business("INSUFFICIENT_FUNDS"),
                server = ServerError(httpCode = 200, code = "INSUFFICIENT_FUNDS"),
            ),
        )
        val flow = flow()
        flow.start(amountSum = 84_000)

        flow.pinChanged(PIN)

        assertEquals(
            WalletPaymentRejection.InsufficientFunds(missingSum = null),
            flow.state.value?.rejection,
        )
    }

    @Test
    fun `an unknown refusal keeps the server text and offers a retry`() = runTest {
        defaultResponse = ApiResult.Failure(
            ApiFailure(
                error = ApiError.Business("ITEM_OUT_OF_STOCK"),
                server = ServerError(httpCode = 200, code = "ITEM_OUT_OF_STOCK", message = "Osh tugadi"),
            ),
        )
        val flow = flow()
        flow.start(amountSum = 84_000)

        flow.pinChanged(PIN)

        val rejection = flow.state.value?.rejection as WalletPaymentRejection.Declined
        assertEquals("Osh tugadi", rejection.failure.serverMessage)
        assertTrue(flow.state.value?.canRetry == true)
    }

    @Test
    fun `a retry after a broken connection reuses the same idempotency key`() = runTest {
        // Соединение могло оборваться уже после того, как сервер списал деньги:
        // второй ключ означал бы второй заказ и второе списание.
        responses += ApiResult.Failure(ApiError.NoConnection)
        val flow = flow()
        flow.start(amountSum = 84_000)
        flow.pinChanged(PIN)

        flow.retry()

        assertEquals(2, sentKeys.size)
        assertEquals(sentKeys[0], sentKeys[1])
    }

    @Test
    fun `a second start while the first payment is open sends nothing new`() = runTest {
        val flow = flow()
        flow.start(amountSum = 84_000)

        flow.start(amountSum = 84_000)
        flow.pinChanged(PIN)

        assertEquals(1, sentKeys.size)
    }

    @Test
    fun `nothing is sent after a payment went through`() = runTest {
        val flow = flow()
        flow.start(amountSum = 84_000)
        flow.pinChanged(PIN)

        // Возврат на экран и повторное нажатие «оформить»: заказ уже создан.
        flow.start(amountSum = 84_000)

        assertEquals(1, sentKeys.size)
        assertNull(flow.state.value)
    }

    @Test
    fun `a paid order reaches the caller`() = runTest {
        val flow = flow()
        flow.start(amountSum = 84_000)

        flow.pinChanged(PIN)

        // Событие буферизовано: экран узнаёт об оплате, даже если подписался
        // на неё позже отправки.
        assertEquals("o-1", flow.paid.first())
    }

    @Test
    fun `biometric confirmation replaces the pin`() = runTest {
        policy.method = PaymentConfirmationMethod.Biometric
        val flow = flow()
        flow.start(amountSum = 84_000)

        flow.biometricConfirmed()

        assertEquals(1, sentKeys.size)
    }

    @Test
    fun `a closed biometric prompt falls back to the pin`() = runTest {
        // Мокрый палец — не повод отменять заказ: PIN настроен у всех, кто
        // прошёл онбординг.
        policy.method = PaymentConfirmationMethod.Biometric
        val flow = flow()
        flow.start(amountSum = 84_000)

        flow.biometricRejected()

        assertEquals(PaymentConfirmationMethod.Pin, flow.state.value?.method)
        assertEquals(emptyList<String>(), sentKeys)

        flow.pinChanged(PIN)

        assertEquals(1, sentKeys.size)
    }

    @Test
    fun `without a pin and biometrics the payment goes straight through`() = runTest {
        // Иначе человек запирается в шторке, из которой нет выхода.
        policy.method = null
        val flow = flow()

        flow.start(amountSum = 84_000)

        assertEquals(1, sentKeys.size)
    }

    @Test
    fun `dismissing the sheet starts the next payment with a new key`() = runTest {
        responses += ApiResult.Failure(ApiError.NoConnection)
        val flow = flow()
        flow.start(amountSum = 84_000)
        flow.pinChanged(PIN)

        flow.dismiss()
        flow.start(amountSum = 42_000)
        flow.pinChanged(PIN)

        assertEquals(2, sentKeys.size)
        assertTrue(sentKeys[0] != sentKeys[1])
    }

    private fun TestScope.flow(): WalletPaymentFlow<String> = WalletPaymentFlow(
        walletRepository = walletRepository,
        pinStorage = pinStorage,
        confirmationPolicy = policy,
        // Немедленная отправка: шаги оплаты — цепочка корутин, и ждать их
        // руками в каждом тесте значило бы проверять диспетчер, а не оплату.
        scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        newKey = { "key-${keyCounter++}" },
        submit = { key ->
            sentKeys += key
            responses.removeFirstOrNull() ?: defaultResponse
        },
    )

    private var keyCounter = 1

    private companion object {
        const val PIN = "123456"
    }
}
