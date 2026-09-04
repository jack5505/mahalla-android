package uz.mahalla.feature.wallet.ui

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.wallet.domain.Wallet
import uz.mahalla.feature.wallet.domain.WalletTransaction

/**
 * Состояние кошелька (issue #62).
 *
 * Баланс и история — два независимых состояния: история — вторая ручка, и её
 * отказ не повод спрятать баланс, который уже приехал. Обратное тоже верно.
 *
 * @param isRefreshing pull-to-refresh поверх уже показанных данных: скелетон
 * при нём не нужен, иначе каждое обновление выглядит как открытие экрана.
 * @param loadMoreFailure догрузка страницы истории не удалась — вместе с
 * причиной, чтобы кнопка «повторить» не осталась без объяснения (issue #34).
 */
data class WalletState(
    val wallet: ScreenState<Wallet> = ScreenState.Loading,
    val transactions: ScreenState<List<WalletTransaction>> = ScreenState.Loading,
    val isRefreshing: Boolean = false,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadMoreFailure: ApiFailure? = null,
) : UiState

sealed interface WalletEvent : UiEvent {
    /** Экран вернулся на передний план: баланс мог измениться в другом месте. */
    data object ScreenResumed : WalletEvent

    data object Refreshed : WalletEvent
    data object Retry : WalletEvent
    data object TransactionsRetry : WalletEvent
    data object LoadMore : WalletEvent
}

/** Экран кошелька пока ничего не открывает: пополнение — задача 8.2 (#12). */
sealed interface WalletEffect : UiEffect
