package uz.mahalla.feature.wallet.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
import uz.mahalla.feature.wallet.domain.TopUpError
import uz.mahalla.feature.wallet.domain.TopUpOrder
import uz.mahalla.feature.wallet.domain.TopUpProvider
import uz.mahalla.feature.wallet.domain.Wallet
import uz.mahalla.feature.wallet.domain.WalletAmounts
import uz.mahalla.feature.wallet.domain.WalletStatus
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

    // --- Пополнение (issue #93) ---

    /**
     * Делитель единиц бэкенда берётся из уже приехавшего баланса: без него
     * неизвестно ни сколько отправлять, ни какой минимум обещать.
     */
    @Test
    fun `top up sheet takes the scale from the loaded balance`() = runTest {
        val repository = FakeWalletRepository(
            Wallet(balanceSum = 500_000, availableSum = 500_000, amountScale = 1L),
        )
        val viewModel = WalletViewModel(repository)

        viewModel.onEvent(WalletEvent.TopUpClicked)

        val topUp = requireNotNull(viewModel.state.value.topUp)
        assertEquals(1L, topUp.scale)
        assertEquals(100_000L, topUp.minAmountSum)
    }

    /** Пока баланс не приехал, пополнять нечего: делителя нет. */
    @Test
    fun `top up is not offered without a balance`() = runTest {
        val repository = FakeWalletRepository()
        repository.wallet = ApiResult.Failure(ApiError.NoConnection)
        val viewModel = WalletViewModel(repository)

        viewModel.onEvent(WalletEvent.TopUpClicked)

        assertFalse(viewModel.state.value.canTopUp)
        assertEquals(null, viewModel.state.value.topUp)
    }

    /** Заблокированному кошельку платёж всё равно откажут. */
    @Test
    fun `top up is not offered for a blocked wallet`() = runTest {
        val repository = FakeWalletRepository(Wallet(status = WalletStatus.Blocked))

        assertFalse(WalletViewModel(repository).state.value.canTopUp)
    }

    /**
     * Пустое поле не подсвечивается сразу после открытия шторки: это ругань за
     * то, что человек ещё не начал.
     */
    @Test
    fun `reasons are shown only after the first attempt`() = runTest {
        val repository = FakeWalletRepository()
        val viewModel = WalletViewModel(repository)
        viewModel.onEvent(WalletEvent.TopUpClicked)

        assertTrue(requireNotNull(viewModel.state.value.topUp).visibleErrors.isEmpty())

        viewModel.onEvent(WalletEvent.TopUpSubmitted)

        val topUp = requireNotNull(viewModel.state.value.topUp)
        assertEquals(
            setOf(TopUpError.AmountRequired, TopUpError.ProviderRequired),
            topUp.visibleErrors,
        )
        // Незаполненный черновик в репозиторий не уходит вовсе.
        assertTrue(repository.topUpRequests.isEmpty())
    }

    @Test
    fun `a filled draft opens the payment form of the provider`() = runTest {
        val repository = FakeWalletRepository()
        repository.topUp = ApiResult.Success(TopUpOrder("https://checkout.paycom.uz/abc"))
        val viewModel = WalletViewModel(repository)

        viewModel.onEvent(WalletEvent.TopUpClicked)
        viewModel.onEvent(WalletEvent.TopUpAmountChanged("250 000"))
        viewModel.onEvent(WalletEvent.TopUpProviderSelected(TopUpProvider.Payme))
        viewModel.onEvent(WalletEvent.TopUpSubmitted)

        // Сумма уходит в сумах, вместе с делителем: перевод в единицы
        // бэкенда — дело репозитория.
        assertEquals(
            listOf(Triple(250_000L, TopUpProvider.Payme, WalletAmounts.TIYIN_IN_SOM)),
            repository.topUpRequests,
        )
        // Эффекты складываются в буферизованный канал, поэтому первый уже там.
        assertEquals(
            WalletEffect.OpenPaymentForm("https://checkout.paycom.uz/abc"),
            viewModel.effects.first(),
        )
        val state = viewModel.state.value
        // Шторка закрывается: возвращаться человеку надо на баланс.
        assertEquals(null, state.topUp)
        assertEquals(250_000L, state.paymentStarted?.amountSum)
    }

    /**
     * Деньги зачисляет колбэк провайдера, поэтому баланс на клиенте не
     * прибавляется — он перечитывается по возвращении на экран.
     */
    @Test
    fun `balance is reread after the payment, not counted on the client`() = runTest {
        val repository = FakeWalletRepository(Wallet(balanceSum = 0, availableSum = 0))
        val viewModel = WalletViewModel(repository)
        viewModel.onEvent(WalletEvent.TopUpClicked)
        viewModel.onEvent(WalletEvent.TopUpAmountChanged("250000"))
        viewModel.onEvent(WalletEvent.TopUpProviderSelected(TopUpProvider.Click))
        viewModel.onEvent(WalletEvent.TopUpSubmitted)

        assertEquals(0L, (viewModel.state.value.wallet as ScreenState.Content).data.availableSum)

        repository.wallet = ApiResult.Success(Wallet(balanceSum = 250_000, availableSum = 250_000))
        viewModel.onEvent(WalletEvent.ScreenResumed)

        assertEquals(2, repository.walletCount)
        assertEquals(
            250_000L,
            (viewModel.state.value.wallet as ScreenState.Content).data.availableSum,
        )
        // Плашка остаётся: деньги могли и не дойти, и об этом надо сказать.
        assertEquals(250_000L, viewModel.state.value.paymentStarted?.amountSum)
    }

    /** Отказ остаётся в шторке рядом с набранной суммой (issue #34). */
    @Test
    fun `a refused payment keeps the sheet and the typed amount`() = runTest {
        val repository = FakeWalletRepository()
        repository.topUp = ApiResult.Failure(
            ApiFailure(
                ApiError.Business("PROVIDER_UNAVAILABLE"),
                ServerError(httpCode = 502, message = "Payme javob bermadi"),
            ),
        )
        val viewModel = WalletViewModel(repository)
        viewModel.onEvent(WalletEvent.TopUpClicked)
        viewModel.onEvent(WalletEvent.TopUpAmountChanged("250000"))
        viewModel.onEvent(WalletEvent.TopUpProviderSelected(TopUpProvider.Uzum))

        viewModel.onEvent(WalletEvent.TopUpSubmitted)

        val topUp = requireNotNull(viewModel.state.value.topUp)
        assertEquals("250000", topUp.draft.amountText)
        assertEquals("Payme javob bermadi", topUp.failure?.serverMessage)
        assertFalse(topUp.isSubmitting)
        assertEquals(null, viewModel.state.value.paymentStarted)

        // Правка суммы снимает прошлый отказ: он был про другую сумму.
        viewModel.onEvent(WalletEvent.TopUpAmountChanged("300000"))
        assertEquals(null, requireNotNull(viewModel.state.value.topUp).failure)
    }

    /** Тап без последствий читается как сломанная кнопка. */
    @Test
    fun `a device without a browser is told about it`() = runTest {
        val repository = FakeWalletRepository()
        val viewModel = WalletViewModel(repository)
        viewModel.onEvent(WalletEvent.TopUpClicked)
        viewModel.onEvent(WalletEvent.TopUpAmountChanged("250000"))
        viewModel.onEvent(WalletEvent.TopUpProviderSelected(TopUpProvider.Payme))
        viewModel.onEvent(WalletEvent.TopUpSubmitted)

        viewModel.onEvent(WalletEvent.PaymentOpenFailed)

        // Плашка «платёж отправлен» была бы неправдой: до формы оплаты дело не
        // дошло. Сообщение живёт на экране — шторка к этому моменту закрыта.
        val state = viewModel.state.value
        assertEquals(null, state.paymentStarted)
        assertTrue(state.paymentOpenFailed)

        // Новая попытка начинается с чистого экрана.
        viewModel.onEvent(WalletEvent.TopUpClicked)
        assertFalse(viewModel.state.value.paymentOpenFailed)
    }

    @Test
    fun `the notice about a started payment is dismissable`() = runTest {
        val repository = FakeWalletRepository()
        val viewModel = WalletViewModel(repository)
        viewModel.onEvent(WalletEvent.TopUpClicked)
        viewModel.onEvent(WalletEvent.TopUpAmountChanged("250000"))
        viewModel.onEvent(WalletEvent.TopUpProviderSelected(TopUpProvider.Payme))
        viewModel.onEvent(WalletEvent.TopUpSubmitted)
        assertTrue(viewModel.state.value.paymentStarted != null)

        viewModel.onEvent(WalletEvent.PaymentNoticeDismissed)

        assertEquals(null, viewModel.state.value.paymentStarted)
    }

    private fun page(
        items: List<WalletTransaction>,
        hasMore: Boolean,
    ): ApiResult<WalletTransactionPage> =
        ApiResult.Success(WalletTransactionPage(items = items, hasMore = hasMore))

    private fun transaction(id: String) = WalletTransaction(id = id, amountSum = 1_000)
}
