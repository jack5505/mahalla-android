package uz.mahalla.feature.business.ui.earnings

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.business.domain.Payout
import uz.mahalla.feature.business.domain.PayoutDraft
import uz.mahalla.feature.business.domain.PayoutError
import uz.mahalla.feature.wallet.domain.Wallet
import uz.mahalla.feature.wallet.domain.WalletStatus
import uz.mahalla.feature.wallet.domain.WalletTransaction

/**
 * Состояние экрана «Заработок» (issue #290): баланс бизнес-кошелька, история
 * начислений и заявка на вывод.
 *
 * Баланс и история — отдельные состояния, как и в личном кошельке
 * ([uz.mahalla.feature.wallet.ui.WalletState]): отказ истории не должен
 * прятать баланс, который уже приехал.
 */
data class BusinessEarningsState(
    val placeName: String = "",
    val wallet: ScreenState<Wallet> = ScreenState.Loading,
    val transactions: ScreenState<List<WalletTransaction>> = ScreenState.Loading,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadMoreFailure: ApiFailure? = null,
    val isRefreshing: Boolean = false,
    val payout: PayoutSheetState? = null,
    /** Заявка, отправленная только что — своя карточка над историей. */
    val lastPayout: Payout? = null,
) : UiState {

    /** Баланс, который уже приехал: без него нечего выводить и нечем проверить блокировку. */
    val loadedWallet: Wallet? get() = (wallet as? ScreenState.Content)?.data

    /**
     * Заявку можно завести только когда баланс приехал — тем же правилом, что
     * и пополнение личного кошелька ([uz.mahalla.feature.wallet.ui.WalletState.canTopUp]):
     * заблокированному кошельку бэкенд всё равно откажет.
     */
    val canRequestPayout: Boolean
        get() = loadedWallet?.status?.let { it != WalletStatus.Blocked } == true
}

/**
 * Шторка заявки на вывод. Ошибка формы показывается сразу — форма короткая,
 * ходить за ней в сеть незачем (issue #84).
 */
data class PayoutSheetState(
    val draft: PayoutDraft = PayoutDraft(),
    val showErrors: Boolean = false,
    val errors: Set<PayoutError> = emptySet(),
    val isSubmitting: Boolean = false,
    /** Отказ сервера — код недостатка средств и любой другой показываются его же текстом. */
    val failure: ApiFailure? = null,
) {
    val visibleErrors: Set<PayoutError> get() = if (showErrors) errors else emptySet()
}

sealed interface BusinessEarningsEvent : UiEvent {
    data object ScreenResumed : BusinessEarningsEvent
    data object Refreshed : BusinessEarningsEvent
    data object Retry : BusinessEarningsEvent

    /** Повтор только истории: баланс уже на экране, перезапрашивать его незачем. */
    data object TransactionsRetry : BusinessEarningsEvent
    data object LoadMore : BusinessEarningsEvent

    data object PayoutClicked : BusinessEarningsEvent
    data object PayoutDismissed : BusinessEarningsEvent
    data class PayoutAmountChanged(val value: String) : BusinessEarningsEvent
    data class PayoutCardNumberChanged(val value: String) : BusinessEarningsEvent
    data object PayoutSubmitted : BusinessEarningsEvent
    data object LastPayoutDismissed : BusinessEarningsEvent
}

/** Навигация со «Заработка» — только назад, её ведёт сам `NavHost`. */
sealed interface BusinessEarningsEffect : UiEffect
