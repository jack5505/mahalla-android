package uz.mahalla.feature.business.ui.earnings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.toScreenState
import uz.mahalla.feature.business.data.BusinessRepository
import uz.mahalla.feature.business.domain.PayoutDraft
import uz.mahalla.feature.business.domain.PayoutValidator
import uz.mahalla.feature.wallet.domain.WalletTransaction
import uz.mahalla.feature.wallet.domain.WalletTransactionPage
import uz.mahalla.navigation.BusinessArgs
import javax.inject.Inject

/**
 * «Заработок» бизнес-панели (issue #290): баланс бизнес-кошелька, история
 * начислений (комиссия и возврат видны в ней обычными строками — своего
 * разбора для них нет, см. [BusinessRepository.earningsHistory]) и заявка на
 * вывод.
 *
 * Устроен как [uz.mahalla.feature.wallet.ui.WalletViewModel]: баланс и
 * история — независимые ручки и грузятся параллельно, отказ одной не прячет
 * другую.
 */
@HiltViewModel
class BusinessEarningsViewModel @Inject constructor(
    private val repository: BusinessRepository,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<BusinessEarningsState, BusinessEarningsEvent, BusinessEarningsEffect>(
    BusinessEarningsState(
        placeName = savedStateHandle.get<String>(BusinessArgs.PLACE_NAME).orEmpty(),
    ),
) {

    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null
    private var loadedPage = 0

    init {
        load()
    }

    override fun onEvent(event: BusinessEarningsEvent) {
        when (event) {
            BusinessEarningsEvent.ScreenResumed -> onScreenResumed(
                isLoadInFlight = { loadJob?.isActive == true },
                load = { load(showLoading = false) },
            )

            BusinessEarningsEvent.Refreshed -> load(showLoading = false, refreshing = true)
            BusinessEarningsEvent.Retry -> load()
            BusinessEarningsEvent.TransactionsRetry -> loadHistory()
            BusinessEarningsEvent.LoadMore -> loadMore()

            // Шторка открывается только поверх приехавшего баланса: без него
            // непонятно ни сколько на счету, ни заблокирован ли вывод.
            BusinessEarningsEvent.PayoutClicked -> if (currentState.canRequestPayout) {
                updateState { copy(payout = PayoutSheetState()) }
            }

            BusinessEarningsEvent.PayoutDismissed -> updateState { copy(payout = null) }

            is BusinessEarningsEvent.PayoutAmountChanged -> updatePayout {
                revalidated(draft.copy(amountText = event.value))
            }

            is BusinessEarningsEvent.PayoutCardNumberChanged -> updatePayout {
                revalidated(draft.copy(cardNumberText = event.value))
            }

            BusinessEarningsEvent.PayoutSubmitted -> submitPayout()
            BusinessEarningsEvent.LastPayoutDismissed -> updateState { copy(lastPayout = null) }
        }
    }

    /**
     * @param showLoading скелетон вместо содержимого. При обновлении поверх
     * уже показанных данных он не нужен: баланс мигал бы на каждом возврате.
     */
    private fun load(showLoading: Boolean = true, refreshing: Boolean = false) {
        if (showLoading) updateState { copy(wallet = ScreenState.Loading) }
        if (refreshing) updateState { copy(isRefreshing = true) }
        resetHistory(showLoading = showLoading)
        loadJob = viewModelScope.launch {
            // Баланс и история — независимые ручки: последовательный запрос
            // удвоил бы время до первого экрана без всякой причины.
            val balance = async { repository.earningsWallet() }
            val history = async { repository.earningsHistory(page = 0) }
            val walletState = balance.await().toScreenState()
            updateState { copy(wallet = walletState) }
            applyHistory(history.await())
            if (refreshing) updateState { copy(isRefreshing = false) }
        }
    }

    private fun loadHistory() {
        resetHistory(showLoading = true)
        viewModelScope.launch { applyHistory(repository.earningsHistory(page = 0)) }
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
     * Догрузка страницы истории — то же правило, что у личного кошелька:
     * провал не стирает уже показанные операции, а хвост списка переходит в
     * состояние «повторить».
     */
    private fun loadMore() {
        val state = currentState
        if (!state.hasMore || state.isLoadingMore) return
        val loaded = state.transactions as? ScreenState.Content ?: return
        if (loadMoreJob?.isActive == true) return

        val nextPage = loadedPage + 1
        updateState { copy(isLoadingMore = true, loadMoreFailure = null) }
        loadMoreJob = viewModelScope.launch {
            when (val result = repository.earningsHistory(page = nextPage)) {
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

    /** Операция может приехать на двух соседних страницах — дедупликация по id. */
    private fun appended(
        current: List<WalletTransaction>,
        next: List<WalletTransaction>,
    ): List<WalletTransaction> {
        val known = current.mapTo(mutableSetOf(), WalletTransaction::id)
        return current + next.filter { known.add(it.id) }
    }

    /** Заведение заявки. Проверка черновика — и здесь, и в репозитории (issue #84). */
    private fun submitPayout() {
        val payout = currentState.payout ?: return
        if (payout.isSubmitting) return
        val errors = PayoutValidator.validate(payout.draft)
        if (errors.isNotEmpty()) {
            updatePayout { copy(showErrors = true, errors = errors) }
            return
        }
        // Провалидированный черновик гарантирует непустую сумму — те же
        // условия, что и в PayoutValidator.validate выше.
        val amountSum = checkNotNull(payout.draft.amountSum)
        val cardDigits = payout.draft.cardDigits

        updatePayout { copy(isSubmitting = true, failure = null) }
        viewModelScope.launch {
            when (val result = repository.requestPayout(amountSum, cardDigits)) {
                // Отказ (в т.ч. недостаток средств) остаётся в шторке текстом
                // сервера — свой текст здесь не придумывается (issue #290).
                is ApiResult.Failure -> updatePayout {
                    copy(isSubmitting = false, failure = result.failure)
                }

                is ApiResult.Success -> {
                    updateState { copy(payout = null, lastPayout = result.data) }
                    // Только баланс, без истории: полный `load()` сбросил бы
                    // догруженные страницы истории обратно на первую — а
                    // догрузку могли открыть до заявки (нашло ревью).
                    refreshWallet()
                }
            }
        }
    }

    private fun refreshWallet() {
        viewModelScope.launch {
            val walletState = repository.earningsWallet().toScreenState()
            updateState { copy(wallet = walletState) }
        }
    }

    private fun PayoutSheetState.revalidated(next: PayoutDraft): PayoutSheetState = copy(
        draft = next,
        errors = PayoutValidator.validate(next),
        failure = null,
    )

    private inline fun updatePayout(crossinline transform: PayoutSheetState.() -> PayoutSheetState) {
        updateState { copy(payout = payout?.transform()) }
    }
}
