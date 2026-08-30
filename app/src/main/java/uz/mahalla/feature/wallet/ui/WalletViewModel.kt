package uz.mahalla.feature.wallet.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.isLoading
import uz.mahalla.core.ui.state.toScreenState
import uz.mahalla.feature.wallet.data.WalletRepository
import uz.mahalla.feature.wallet.domain.WalletTransaction
import uz.mahalla.feature.wallet.domain.WalletTransactionPage
import javax.inject.Inject

/**
 * Кошелёк: баланс и история операций (issue #62, задача 8.1 эпика #12).
 *
 * До этого экран показывал зашитое число, одинаковое для всех. Никакого
 * локального «последнего известного баланса» здесь нет: экран либо показывает
 * ответ сервера, либо честно говорит, что не смог его получить.
 */
@HiltViewModel
class WalletViewModel @Inject constructor(
    private val repository: WalletRepository,
) : MviViewModel<WalletState, WalletEvent, WalletEffect>(WalletState()) {

    private var loadMoreJob: Job? = null
    private var loadedPage = 0

    init {
        load()
    }

    override fun onEvent(event: WalletEvent) {
        when (event) {
            // Возврат на экран: заказ мог быть оплачен, пока приложение было в
            // фоне. Пока идёт загрузка, перезапрашивать нечего — ответ приедет
            // на уже сменившееся состояние.
            WalletEvent.ScreenResumed ->
                if (!currentState.wallet.isLoading && !currentState.isRefreshing) {
                    load(showLoading = false)
                }

            WalletEvent.Refreshed -> load(showLoading = false, refreshing = true)

            WalletEvent.Retry -> load()

            // История отдельной ручкой: её повтор не должен дёргать баланс,
            // который уже на экране.
            WalletEvent.TransactionsRetry -> loadTransactions()

            WalletEvent.LoadMore -> loadMore()
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
        viewModelScope.launch {
            // Баланс и история — две независимые ручки: последовательный
            // запрос удвоил бы время до первого экрана без всякой причины.
            val balance = async { repository.wallet() }
            val history = async { repository.transactions(page = 0) }
            val walletState = balance.await().toScreenState()
            updateState { copy(wallet = walletState) }
            applyHistory(history.await())
            // Индикатор снимается, когда приехали оба ответа: иначе он гаснет
            // над списком, который ещё грузится.
            if (refreshing) updateState { copy(isRefreshing = false) }
        }
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
}
