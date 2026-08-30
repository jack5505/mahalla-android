package uz.mahalla.feature.wallet.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.ServerError
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.wallet.domain.Wallet
import uz.mahalla.feature.wallet.domain.WalletTransaction
import uz.mahalla.feature.wallet.domain.WalletTransactionPage
import uz.mahalla.testutil.FakeWalletRepository
import uz.mahalla.testutil.MainDispatcherRule

/**
 * Экран кошелька (issue #62): баланс и история — две независимые ручки, и
 * отказ одной не должен прятать другую.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WalletViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun `balance and history are loaded on open`() = runTest {
        val repository = FakeWalletRepository(Wallet(balanceSum = 500_000, availableSum = 480_000))
        repository.defaultPage = page(listOf(transaction("t-1")), hasMore = false)

        val state = WalletViewModel(repository).state.value

        assertEquals(480_000L, (state.wallet as ScreenState.Content).data.availableSum)
        assertEquals(
            listOf("t-1"),
            (state.transactions as ScreenState.Content).data.map(WalletTransaction::id),
        )
        assertFalse(state.hasMore)
        assertEquals(listOf(0), repository.requestedPages)
    }

    @Test
    fun `an empty history is not an error`() = runTest {
        val repository = FakeWalletRepository()

        val state = WalletViewModel(repository).state.value

        assertTrue(state.wallet is ScreenState.Content)
        assertTrue(state.transactions is ScreenState.Empty)
    }

    @Test
    fun `a broken history does not hide the balance`() = runTest {
        val repository = FakeWalletRepository()
        repository.defaultPage = ApiResult.Failure(ApiError.Timeout)

        val state = WalletViewModel(repository).state.value

        assertTrue(state.wallet is ScreenState.Content)
        assertEquals(ApiError.Timeout, (state.transactions as ScreenState.Error).error)
    }

    @Test
    fun `the server message reaches the balance error`() = runTest {
        val repository = FakeWalletRepository()
        repository.wallet = ApiResult.Failure(
            ApiFailure(
                error = ApiError.Forbidden,
                server = ServerError(httpCode = 403, code = "WALLET_BLOCKED", message = "Bloklangan"),
            ),
        )

        val state = WalletViewModel(repository).state.value

        // Текст сервера точнее нашего «нет доступа» (issue #34).
        assertEquals("Bloklangan", (state.wallet as ScreenState.Error).failure.serverMessage)
        assertTrue(state.transactions is ScreenState.Empty)
    }

    @Test
    fun `retry asks the failed endpoint only`() = runTest {
        val repository = FakeWalletRepository()
        repository.defaultPage = ApiResult.Failure(ApiError.Timeout)
        val viewModel = WalletViewModel(repository)

        repository.defaultPage = page(listOf(transaction("t-1")), hasMore = false)
        viewModel.onEvent(WalletEvent.TransactionsRetry)

        assertTrue(viewModel.state.value.transactions is ScreenState.Content)
        // Баланс уже на экране — дёргать его повтором истории незачем.
        assertEquals(1, repository.walletCount)
        assertEquals(listOf(0, 0), repository.requestedPages)
    }

    @Test
    fun `load more appends the next page and stops at the last one`() = runTest {
        val repository = FakeWalletRepository()
        repository.pages[0] = page(listOf(transaction("t-1")), hasMore = true)
        repository.pages[1] = page(listOf(transaction("t-2")), hasMore = false)
        val viewModel = WalletViewModel(repository)

        assertTrue(viewModel.state.value.hasMore)
        viewModel.onEvent(WalletEvent.LoadMore)

        val state = viewModel.state.value
        assertEquals(
            listOf("t-1", "t-2"),
            (state.transactions as ScreenState.Content).data.map(WalletTransaction::id),
        )
        assertFalse(state.hasMore)
        assertFalse(state.isLoadingMore)

        // Дальше догружать нечего: повторное событие в сеть не идёт.
        viewModel.onEvent(WalletEvent.LoadMore)
        assertEquals(listOf(0, 1), repository.requestedPages)
    }

    @Test
    fun `an operation seen twice does not break the list`() = runTest {
        val repository = FakeWalletRepository()
        repository.pages[0] = page(listOf(transaction("t-1")), hasMore = true)
        // История пополнилась между запросами — та же операция уехала на
        // вторую страницу. Дубликат ключа уронил бы LazyColumn.
        repository.pages[1] = page(listOf(transaction("t-1"), transaction("t-2")), hasMore = false)
        val viewModel = WalletViewModel(repository)

        viewModel.onEvent(WalletEvent.LoadMore)

        assertEquals(
            listOf("t-1", "t-2"),
            (viewModel.state.value.transactions as ScreenState.Content).data
                .map(WalletTransaction::id),
        )
    }

    @Test
    fun `a failed load more keeps the list and offers a retry`() = runTest {
        val repository = FakeWalletRepository()
        repository.pages[0] = page(listOf(transaction("t-1")), hasMore = true)
        repository.pages[1] = ApiResult.Failure(ApiError.NoConnection)
        val viewModel = WalletViewModel(repository)

        viewModel.onEvent(WalletEvent.LoadMore)

        val state = viewModel.state.value
        // Потерять показанную историю из-за одного неудачного запроса хуже,
        // чем не получить следующую страницу.
        assertEquals(1, (state.transactions as ScreenState.Content).data.size)
        assertEquals(ApiError.NoConnection, state.loadMoreFailure?.error)
        assertFalse(state.isLoadingMore)

        repository.pages[1] = page(listOf(transaction("t-2")), hasMore = false)
        viewModel.onEvent(WalletEvent.LoadMore)

        assertEquals(2, (viewModel.state.value.transactions as ScreenState.Content).data.size)
    }

    @Test
    fun `returning to the screen rereads the balance`() = runTest {
        val repository = FakeWalletRepository()
        val viewModel = WalletViewModel(repository)

        repository.wallet = ApiResult.Success(Wallet(balanceSum = 1, availableSum = 1))
        viewModel.onEvent(WalletEvent.ScreenResumed)

        // Заказ мог быть оплачен, пока приложение было в фоне.
        assertEquals(2, repository.walletCount)
        assertEquals(1L, (viewModel.state.value.wallet as ScreenState.Content).data.availableSum)
    }

    @Test
    fun `refresh does not blank the screen and ends when both answers arrive`() = runTest {
        val repository = FakeWalletRepository()
        repository.defaultPage = page(listOf(transaction("t-1")), hasMore = false)
        val viewModel = WalletViewModel(repository)

        viewModel.onEvent(WalletEvent.Refreshed)

        val state = viewModel.state.value
        assertFalse(state.isRefreshing)
        // Скелетона при pull-to-refresh нет: данные остаются на месте.
        assertTrue(state.wallet is ScreenState.Content)
        assertTrue(state.transactions is ScreenState.Content)
        assertEquals(2, repository.walletCount)
    }

    private fun page(
        items: List<WalletTransaction>,
        hasMore: Boolean,
    ): ApiResult<WalletTransactionPage> =
        ApiResult.Success(WalletTransactionPage(items = items, hasMore = hasMore))

    private fun transaction(id: String) = WalletTransaction(id = id, amountSum = 1_000)
}
