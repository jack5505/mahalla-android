package uz.mahalla.feature.wallet.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.toScreenState
import uz.mahalla.feature.subscription.data.SubscriptionRepository
import uz.mahalla.feature.subscription.domain.Subscription
import uz.mahalla.feature.wallet.data.PaymentsRepository
import uz.mahalla.feature.wallet.data.WalletRepository
import uz.mahalla.feature.wallet.domain.PaymentTransaction
import uz.mahalla.feature.wallet.domain.PaymentTransactionPage
import uz.mahalla.feature.wallet.domain.TopUpDraft
import uz.mahalla.feature.wallet.domain.TopUpValidator
import uz.mahalla.feature.wallet.domain.WalletTransaction
import uz.mahalla.feature.wallet.domain.WalletTransactionPage
import javax.inject.Inject

/**
 * Кошелёк: баланс, история операций и пополнение (issue #62 и #93, задачи 8.1
 * и 8.2 эпика #12).
 *
 * До этого экран показывал зашитое число, одинаковое для всех. Никакого
 * локального «последнего известного баланса» здесь нет: экран либо показывает
 * ответ сервера, либо честно говорит, что не смог его получить. По той же
 * причине пополнение не прибавляет сумму к балансу на клиенте: деньги
 * зачисляет колбэк провайдера, и единственный, кто знает, дошли они или нет, —
 * сервер. После возврата из формы оплаты баланс перечитывается.
 */
@HiltViewModel
class WalletViewModel @Inject constructor(
    private val repository: WalletRepository,
    private val subscriptions: SubscriptionRepository,
    private val paymentsRepository: PaymentsRepository,
) : MviViewModel<WalletState, WalletEvent, WalletEffect>(WalletState()) {

    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null
    private var loadedPage = 0
    private var loadMorePaymentsJob: Job? = null
    private var loadedPaymentsPage = 0

    init {
        load()
    }

    override fun onEvent(event: WalletEvent) {
        when (event) {
            // Возврат на экран: заказ мог быть оплачен, пока приложение было в
            // фоне. Защита от дубля (первый resume, два resume подряд) —
            // общая, см. MviViewModel.onScreenResumed (issue #145, #209).
            WalletEvent.ScreenResumed -> onScreenResumed(
                isLoadInFlight = { loadJob?.isActive == true },
                load = { load(showLoading = false) },
            )

            WalletEvent.Refreshed -> load(showLoading = false, refreshing = true)

            WalletEvent.Retry -> load()

            // История отдельной ручкой: её повтор не должен дёргать баланс,
            // который уже на экране.
            WalletEvent.TransactionsRetry -> loadTransactions()

            WalletEvent.LoadMore -> loadMore()

            // Обе вкладки грузятся сразу при открытии экрана: переключение —
            // это просто смена того, что показывать, без похода в сеть.
            is WalletEvent.TabSelected -> updateState { copy(selectedTab = event.tab) }

            WalletEvent.PaymentsRetry -> loadPayments()

            WalletEvent.LoadMorePayments -> loadMorePayments()

            // Шторка открывается только поверх приехавшего баланса: без него
            // человек не знает, сколько у него есть, и пополнять вслепую незачем.
            WalletEvent.TopUpClicked -> currentState.loadedWallet?.let {
                updateState {
                    copy(
                        topUp = TopUpState(),
                        paymentOpenFailed = false,
                    )
                }
            }

            WalletEvent.TopUpDismissed -> updateState { copy(topUp = null) }

            is WalletEvent.TopUpAmountChanged -> updateTopUp {
                // Прошлый отказ сервера снимается на первой же правке: он был
                // про другую сумму.
                revalidated(draft.copy(amountText = event.value))
            }

            is WalletEvent.TopUpProviderSelected -> updateTopUp {
                revalidated(draft.copy(provider = event.provider))
            }

            WalletEvent.TopUpSubmitted -> submitTopUp()

            // До формы оплаты дело не дошло: плашка «платёж отправлен» была бы
            // неправдой, а тап без последствий читается как сломанная кнопка.
            WalletEvent.PaymentOpenFailed -> updateState {
                copy(paymentStarted = null, paymentOpenFailed = true)
            }

            WalletEvent.PaymentNoticeDismissed -> updateState {
                copy(paymentStarted = null, paymentOpenFailed = false)
            }

            WalletEvent.SubscriptionClicked -> emitEffect(WalletEffect.OpenSubscription)
        }
    }

    /**
     * Заведение платежа. Проверка черновика идёт и здесь, и в репозитории: тут
     * — чтобы показать, чего не хватает, там — чтобы в сеть не ушла заведомо
     * отвергаемая сумма.
     */
    private fun submitTopUp() {
        val topUp = currentState.topUp ?: return
        if (topUp.isSubmitting) return
        val provider = topUp.draft.provider
        val amountSum = topUp.draft.amountSum
        val errors = TopUpValidator.validate(topUp.draft)
        if (errors.isNotEmpty() || provider == null || amountSum == null) {
            updateTopUp { copy(showErrors = true, errors = errors) }
            return
        }

        updateTopUp { copy(isSubmitting = true, failure = null) }
        updateState { copy(paymentOpenFailed = false) }
        viewModelScope.launch {
            when (val result = repository.topUp(amountSum, provider)) {
                is ApiResult.Failure -> updateTopUp {
                    copy(isSubmitting = false, failure = result.failure)
                }

                is ApiResult.Success -> {
                    // Шторка закрывается, а не остаётся под браузером: платить
                    // человек уходит в форму провайдера, и возвращаться ему
                    // надо на баланс. Сумма запоминается — по возврате экран
                    // скажет, за какой платёж он ждёт денег.
                    updateState {
                        copy(
                            topUp = null,
                            paymentStarted = PaymentStarted(
                                amountSum = amountSum,
                                provider = provider,
                            ),
                        )
                    }
                    emitEffect(WalletEffect.OpenPaymentForm(result.data.paymentUrl))
                }
            }
        }
    }

    /** Правка черновика: ошибки пересчитываются, прошлый отказ сервера снимается. */
    private fun TopUpState.revalidated(next: TopUpDraft): TopUpState = copy(
        draft = next,
        errors = TopUpValidator.validate(next),
        failure = null,
    )

    private inline fun updateTopUp(crossinline transform: TopUpState.() -> TopUpState) {
        updateState { copy(topUp = topUp?.transform()) }
    }

    /**
     * @param showLoading скелетон вместо содержимого. При обновлении поверх
     * уже показанных данных он не нужен: баланс мигал бы на каждом возврате.
     */
    private fun load(showLoading: Boolean = true, refreshing: Boolean = false) {
        if (showLoading) updateState { copy(wallet = ScreenState.Loading) }
        if (refreshing) updateState { copy(isRefreshing = true) }
        resetHistory(showLoading = showLoading)
        resetPayments(showLoading = showLoading)
        loadJob = viewModelScope.launch {
            // Баланс и история — независимые ручки: последовательный запрос
            // удвоил бы время до первого экрана без всякой причины.
            val balance = async { repository.wallet() }
            val history = async { repository.transactions(page = 0) }
            // Подписка — третья ручка и тоже параллельно: карточка «Mahalla+»
            // стоит над историей, и ждать её последовательно значит держать
            // экран пустым дольше без всякой причины.
            val subscription = async { subscriptions.current() }
            // Платежи (вкладка «Платежи», issue #184) — четвёртая и тоже сразу:
            // переключение вкладки не должно ждать сеть, если история уже
            // загружена вместе с остальным.
            val paymentsHistory = async { paymentsRepository.transactions(page = 0) }
            val walletState = balance.await().toScreenState()
            updateState { copy(wallet = walletState) }
            applyHistory(history.await())
            applySubscription(subscription.await())
            applyPayments(paymentsHistory.await())
            // Индикатор снимается, когда приехали оба ответа: иначе он гаснет
            // над списком, который ещё грузится.
            if (refreshing) updateState { copy(isRefreshing = false) }
        }
    }

    /**
     * Отказ подписки прячет карточку, а не роняет кошелёк: за балансом сюда
     * приходят, за подпиской — нет. Причина отказа при этом теряется, но она
     * относится к блоку, которого на экране всё равно не будет.
     */
    private fun applySubscription(result: ApiResult<Subscription?>) {
        val subscription = (result as? ApiResult.Success)?.data
        updateState { copy(subscription = subscription) }
    }

    private fun loadTransactions() {
        resetHistory(showLoading = true)
        viewModelScope.launch { applyHistory(repository.transactions(page = 0)) }
    }

    private fun resetHistory(showLoading: Boolean) {
        loadMoreJob?.cancel()
        loadedPage = 0
        updateState {
            copy(
                transactions = if (showLoading) ScreenState.Loading else transactions,
                isLoadingMore = false,
                loadMoreFailure = null,
            )
        }
    }

    private fun applyHistory(result: ApiResult<WalletTransactionPage>) {
        when (result) {
            is ApiResult.Failure -> updateState {
                copy(transactions = ScreenState.Error(result.failure), hasMore = false)
            }

            is ApiResult.Success -> updateState {
                copy(
                    transactions = if (result.data.items.isEmpty()) {
                        ScreenState.Empty
                    } else {
                        ScreenState.Content(result.data.items)
                    },
                    hasMore = result.data.hasMore,
                )
            }
        }
    }

    /**
     * Догрузка страницы истории. Провал не стирает уже показанные операции, но
     * и молча дёргать сеть в цикле нельзя: список не вырос, автотриггер по
     * концу списка больше не сработает — поэтому хвост переходит в состояние
     * «повторить» вместе с причиной отказа.
     *
     * Номер загруженной страницы считается локально: сервер, не вернувший
     * `page`, отдаёт дефолтный `0`, и «следующей» навсегда осталась бы первая
     * (issue #53).
     */
    private fun loadMore() {
        val state = currentState
        if (!state.hasMore || state.isLoadingMore) return
        val loaded = state.transactions as? ScreenState.Content ?: return
        if (loadMoreJob?.isActive == true) return

        val nextPage = loadedPage + 1
        updateState { copy(isLoadingMore = true, loadMoreFailure = null) }
        loadMoreJob = viewModelScope.launch {
            when (val result = repository.transactions(page = nextPage)) {
                is ApiResult.Failure -> updateState {
                    copy(isLoadingMore = false, loadMoreFailure = result.failure)
                }

                is ApiResult.Success -> {
                    loadedPage = nextPage
                    updateState {
                        copy(
                            transactions = ScreenState.Content(
                                appended(loaded.data, result.data.items),
                            ),
                            hasMore = result.data.hasMore,
                            isLoadingMore = false,
                        )
                    }
                }
            }
        }
    }

    /**
     * Операция может приехать на двух соседних страницах, если история
     * пополнилась между запросами. В `LazyColumn` это дубликат ключа и
     * падение, поэтому дедупликация по id обязательна.
     */
    private fun appended(
        current: List<WalletTransaction>,
        next: List<WalletTransaction>,
    ): List<WalletTransaction> {
        val known = current.mapTo(mutableSetOf(), WalletTransaction::id)
        return current + next.filter { known.add(it.id) }
    }

    // --- Вкладка «Платежи» (issue #184) ---

    private fun loadPayments() {
        resetPayments(showLoading = true)
        viewModelScope.launch { applyPayments(paymentsRepository.transactions(page = 0)) }
    }

    private fun resetPayments(showLoading: Boolean) {
        loadMorePaymentsJob?.cancel()
        loadedPaymentsPage = 0
        updateState {
            copy(
                payments = if (showLoading) ScreenState.Loading else payments,
                isLoadingMorePayments = false,
                loadMorePaymentsFailure = null,
            )
        }
    }

    private fun applyPayments(result: ApiResult<PaymentTransactionPage>) {
        when (result) {
            is ApiResult.Failure -> updateState {
                copy(payments = ScreenState.Error(result.failure), hasMorePayments = false)
            }

            is ApiResult.Success -> updateState {
                copy(
                    payments = if (result.data.items.isEmpty()) {
                        ScreenState.Empty
                    } else {
                        ScreenState.Content(result.data.items)
                    },
                    hasMorePayments = result.data.hasMore,
                )
            }
        }
    }

    /** Догрузка страницы платежей — то же правило, что у [loadMore]. */
    private fun loadMorePayments() {
        val state = currentState
        if (!state.hasMorePayments || state.isLoadingMorePayments) return
        val loaded = state.payments as? ScreenState.Content ?: return
        if (loadMorePaymentsJob?.isActive == true) return

        val nextPage = loadedPaymentsPage + 1
        updateState { copy(isLoadingMorePayments = true, loadMorePaymentsFailure = null) }
        loadMorePaymentsJob = viewModelScope.launch {
            when (val result = paymentsRepository.transactions(page = nextPage)) {
                is ApiResult.Failure -> updateState {
                    copy(isLoadingMorePayments = false, loadMorePaymentsFailure = result.failure)
                }

                is ApiResult.Success -> {
                    loadedPaymentsPage = nextPage
                    updateState {
                        copy(
                            payments = ScreenState.Content(
                                appendedPayments(loaded.data, result.data.items),
                            ),
                            hasMorePayments = result.data.hasMore,
                            isLoadingMorePayments = false,
                        )
                    }
                }
            }
        }
    }

    private fun appendedPayments(
        current: List<PaymentTransaction>,
        next: List<PaymentTransaction>,
    ): List<PaymentTransaction> {
        val known = current.mapTo(mutableSetOf(), PaymentTransaction::id)
        return current + next.filter { known.add(it.id) }
    }
}
