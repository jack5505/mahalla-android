package uz.mahalla.feature.business.ui.earnings

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.ServerError
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.business.domain.Payout
import uz.mahalla.feature.business.domain.PayoutError
import uz.mahalla.feature.business.domain.PayoutStatus
import uz.mahalla.feature.wallet.domain.TransactionDirection
import uz.mahalla.feature.wallet.domain.Wallet
import uz.mahalla.feature.wallet.domain.WalletTransaction
import uz.mahalla.feature.wallet.domain.WalletTransactionPage
import uz.mahalla.navigation.BusinessArgs
import uz.mahalla.testutil.FakeBusinessRepository
import uz.mahalla.testutil.MainDispatcherRule

/**
 * «Заработок» бизнес-панели (issue #290).
 *
 * Отдельной ручки истории и статусов заявок у бэкенда нет — начисление,
 * комиссия и возврат приезжают обычными записями `wallet/transactions`, и
 * экран обязан показывать их отдельными строками, а не сворачивать.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BusinessEarningsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun `balance and history are loaded on open`() = runTest {
        val repository = FakeBusinessRepository()
        repository.earningsWalletResult =
            ApiResult.Success(Wallet(balanceSum = 500_000, availableSum = 480_000))
        repository.defaultEarningsHistoryPage = page(listOf(transaction("t-1")), hasMore = false)

        val state = viewModel(repository).state.value

        assertEquals(480_000L, (state.wallet as ScreenState.Content).data.availableSum)
        assertEquals(
            listOf("t-1"),
            (state.transactions as ScreenState.Content).data.map(WalletTransaction::id),
        )
        assertEquals(listOf(0), repository.earningsHistoryRequests)
    }

    @Test
    fun `an empty history is not an error`() = runTest {
        val repository = FakeBusinessRepository()

        val state = viewModel(repository).state.value

        assertTrue(state.wallet is ScreenState.Content)
        assertTrue(state.transactions is ScreenState.Empty)
    }

    @Test
    fun `a broken history does not hide the balance`() = runTest {
        val repository = FakeBusinessRepository()
        repository.defaultEarningsHistoryPage = ApiResult.Failure(ApiError.Timeout)

        val state = viewModel(repository).state.value

        assertTrue(state.wallet is ScreenState.Content)
        assertEquals(ApiError.Timeout, (state.transactions as ScreenState.Error).error)
    }

    /**
     * Критерий готовности: начисление и комиссия — две разные записи истории,
     * и обе обязаны остаться на экране, а не свернуться в одно число.
     */
    @Test
    fun `an accrual and its commission stay as two separate lines`() = runTest {
        val repository = FakeBusinessRepository()
        val accrual = transaction("t-earning", direction = TransactionDirection.In, amountSum = 185_000)
        val commission = transaction(
            "t-commission",
            direction = TransactionDirection.Out,
            amountSum = 18_500,
        )
        repository.defaultEarningsHistoryPage = page(listOf(accrual, commission), hasMore = false)

        val transactions = (viewModel(repository).state.value.transactions as ScreenState.Content).data

        assertEquals(listOf("t-earning", "t-commission"), transactions.map(WalletTransaction::id))
        assertEquals(185_000L, transactions[0].signedAmountSum)
        // Комиссия остаётся своей суммой, а не вычитается из начисления на клиенте.
        assertEquals(-18_500L, transactions[1].signedAmountSum)
    }

    /**
     * Критерий готовности: возврат отображается как разворот — отдельная
     * запись с обратным направлением, а не правка прежней.
     */
    @Test
    fun `a refund shows up as a reversal, not as a rewritten accrual`() = runTest {
        val repository = FakeBusinessRepository()
        val accrual = transaction("t-earning", direction = TransactionDirection.In, amountSum = 95_000)
        val refund = transaction("t-refund", direction = TransactionDirection.Out, amountSum = 95_000)
        repository.defaultEarningsHistoryPage = page(listOf(refund, accrual), hasMore = false)

        val transactions = (viewModel(repository).state.value.transactions as ScreenState.Content).data

        assertEquals(2, transactions.size)
        val reversal = transactions.first { it.id == "t-refund" }
        assertEquals(TransactionDirection.Out, reversal.direction)
        assertEquals(-95_000L, reversal.signedAmountSum)
        // Исходное начисление остаётся на месте — возврат его не стирает.
        assertTrue(transactions.any { it.id == "t-earning" })
    }

    @Test
    fun `the payout sheet only opens once the balance has loaded`() = runTest {
        val repository = FakeBusinessRepository()
        repository.earningsWalletResult = ApiResult.Failure(ApiError.Timeout)
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessEarningsEvent.PayoutClicked)

        assertNull(viewModel.state.value.payout)
    }

    @Test
    fun `an empty amount and a short card number are both flagged`() = runTest {
        val viewModel = viewModel(FakeBusinessRepository())
        viewModel.onEvent(BusinessEarningsEvent.PayoutClicked)

        viewModel.onEvent(BusinessEarningsEvent.PayoutCardNumberChanged("4400 1234"))
        viewModel.onEvent(BusinessEarningsEvent.PayoutSubmitted)

        val payout = viewModel.state.value.payout
        assertEquals(
            setOf(PayoutError.AmountRequired, PayoutError.CardNumberInvalid),
            payout?.visibleErrors,
        )
    }

    @Test
    fun `a successful payout closes the sheet and shows its status`() = runTest {
        val repository = FakeBusinessRepository()
        repository.payoutResult = ApiResult.Success(
            Payout(
                id = "p-1",
                amountSum = 250_000,
                cardMasked = "•••• 4400",
                status = PayoutStatus.Pending,
                createdAt = null,
            ),
        )
        val viewModel = viewModel(repository)
        viewModel.onEvent(BusinessEarningsEvent.PayoutClicked)
        viewModel.onEvent(BusinessEarningsEvent.PayoutAmountChanged("250000"))
        viewModel.onEvent(BusinessEarningsEvent.PayoutCardNumberChanged("4400123412341234"))

        viewModel.onEvent(BusinessEarningsEvent.PayoutSubmitted)

        val state = viewModel.state.value
        assertNull(state.payout)
        assertEquals(250_000L, state.lastPayout?.amountSum)
        assertEquals(PayoutStatus.Pending, state.lastPayout?.status)
        assertEquals(listOf(250_000L to "4400123412341234"), repository.payoutRequests)
    }

    /**
     * Нашло ревью: полная перезагрузка после заявки сбросила бы уже
     * догруженные страницы истории обратно на первую. Заявка обновляет
     * только баланс.
     */
    @Test
    fun `a successful payout refreshes the balance without resetting loaded history pages`() =
        runTest {
            val repository = FakeBusinessRepository()
            repository.earningsHistoryPages[0] = page(listOf(transaction("t-1")), hasMore = true)
            repository.earningsHistoryPages[1] = page(listOf(transaction("t-2")), hasMore = false)
            repository.payoutResult = ApiResult.Success(
                Payout(
                    id = "p-1",
                    amountSum = 50_000,
                    cardMasked = null,
                    status = PayoutStatus.Pending,
                    createdAt = null,
                ),
            )
            val viewModel = viewModel(repository)
            viewModel.onEvent(BusinessEarningsEvent.LoadMore)
            val loadedBeforePayout =
                (viewModel.state.value.transactions as ScreenState.Content).data.map(WalletTransaction::id)

            viewModel.onEvent(BusinessEarningsEvent.PayoutClicked)
            viewModel.onEvent(BusinessEarningsEvent.PayoutAmountChanged("50000"))
            viewModel.onEvent(BusinessEarningsEvent.PayoutCardNumberChanged("4400123412341234"))
            viewModel.onEvent(BusinessEarningsEvent.PayoutSubmitted)

            assertEquals(
                loadedBeforePayout,
                (viewModel.state.value.transactions as ScreenState.Content).data.map(WalletTransaction::id),
            )
        }

    /** Критерий готовности: отказ по недостатку средств — текстом сервера. */
    @Test
    fun `a rejection for insufficient funds shows the server message`() = runTest {
        val repository = FakeBusinessRepository()
        repository.payoutResult = ApiResult.Failure(
            ApiFailure(
                error = ApiError.Business("INSUFFICIENT_FUNDS"),
                server = ServerError(
                    httpCode = 200,
                    code = "INSUFFICIENT_FUNDS",
                    message = "Hisobingizda mablag' yetarli emas",
                ),
            ),
        )
        val viewModel = viewModel(repository)
        viewModel.onEvent(BusinessEarningsEvent.PayoutClicked)
        viewModel.onEvent(BusinessEarningsEvent.PayoutAmountChanged("250000"))
        viewModel.onEvent(BusinessEarningsEvent.PayoutCardNumberChanged("4400123412341234"))

        viewModel.onEvent(BusinessEarningsEvent.PayoutSubmitted)

        val payout = viewModel.state.value.payout
        assertEquals("Hisobingizda mablag' yetarli emas", payout?.failure?.serverMessage)
        assertFalse(payout?.isSubmitting ?: true)
        assertNull(viewModel.state.value.lastPayout)
    }

    @Test
    fun `dismissing the last payout card clears it`() = runTest {
        val repository = FakeBusinessRepository()
        repository.payoutResult = ApiResult.Success(
            Payout(
                id = "p-1",
                amountSum = 100_000,
                cardMasked = null,
                status = PayoutStatus.Pending,
                createdAt = null,
            ),
        )
        val viewModel = viewModel(repository)
        viewModel.onEvent(BusinessEarningsEvent.PayoutClicked)
        viewModel.onEvent(BusinessEarningsEvent.PayoutAmountChanged("100000"))
        viewModel.onEvent(BusinessEarningsEvent.PayoutCardNumberChanged("4400123412341234"))
        viewModel.onEvent(BusinessEarningsEvent.PayoutSubmitted)

        viewModel.onEvent(BusinessEarningsEvent.LastPayoutDismissed)

        assertNull(viewModel.state.value.lastPayout)
    }

    @Test
    fun `load more appends the next page and stops at the last one`() = runTest {
        val repository = FakeBusinessRepository()
        repository.earningsHistoryPages[0] = page(listOf(transaction("t-1")), hasMore = true)
        repository.earningsHistoryPages[1] = page(listOf(transaction("t-2")), hasMore = false)
        val viewModel = viewModel(repository)

        viewModel.onEvent(BusinessEarningsEvent.LoadMore)

        val state = viewModel.state.value
        assertEquals(
            listOf("t-1", "t-2"),
            (state.transactions as ScreenState.Content).data.map(WalletTransaction::id),
        )
        assertFalse(state.hasMore)
    }

    private fun viewModel(repository: FakeBusinessRepository) = BusinessEarningsViewModel(
        repository = repository,
        savedStateHandle = SavedStateHandle(
            mapOf(
                BusinessArgs.PLACE_ID to FakeBusinessRepository.PLACE_ID,
                BusinessArgs.PLACE_NAME to "Osh Markazi",
            ),
        ),
    )

    private fun transaction(
        id: String,
        direction: TransactionDirection = TransactionDirection.In,
        amountSum: Long = 10_000,
    ) = WalletTransaction(
        id = id,
        direction = direction,
        amountSum = amountSum,
        signedAmountSum = if (direction == TransactionDirection.Out) -amountSum else amountSum,
    )

    private fun page(items: List<WalletTransaction>, hasMore: Boolean) =
        ApiResult.Success(WalletTransactionPage(items = items, hasMore = hasMore))
}
