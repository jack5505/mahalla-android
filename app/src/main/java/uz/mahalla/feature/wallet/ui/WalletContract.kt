package uz.mahalla.feature.wallet.ui

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.wallet.domain.TopUpDraft
import uz.mahalla.feature.wallet.domain.TopUpError
import uz.mahalla.feature.wallet.domain.TopUpProvider
import uz.mahalla.feature.wallet.domain.Wallet
import uz.mahalla.feature.wallet.domain.WalletStatus
import uz.mahalla.feature.wallet.domain.WalletTopUp
import uz.mahalla.feature.wallet.domain.WalletTransaction

/**
 * Состояние кошелька (issue #62, пополнение — issue #93).
 *
 * Баланс и история — два независимых состояния: история — вторая ручка, и её
 * отказ не повод спрятать баланс, который уже приехал. Обратное тоже верно.
 *
 * @param isRefreshing pull-to-refresh поверх уже показанных данных: скелетон
 * при нём не нужен, иначе каждое обновление выглядит как открытие экрана.
 * @param loadMoreFailure догрузка страницы истории не удалась — вместе с
 * причиной, чтобы кнопка «повторить» не осталась без объяснения (issue #34).
 * @param topUp шторка пополнения; `null` — она закрыта.
 * @param paymentStarted форма оплаты открывалась и человек вернулся: деньги
 * доходят через колбэк провайдера, то есть не мгновенно, и об этом надо
 * сказать словами — иначе неизменившийся баланс читается как потерянный
 * платёж.
 * @param paymentOpenFailed форму оплаты открыть нечем (на устройстве нет
 * браузера). Живёт на экране, а не в шторке: к этому моменту шторка уже
 * закрыта, и сообщение в ней никто бы не увидел.
 */
data class WalletState(
    val wallet: ScreenState<Wallet> = ScreenState.Loading,
    val transactions: ScreenState<List<WalletTransaction>> = ScreenState.Loading,
    val isRefreshing: Boolean = false,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadMoreFailure: ApiFailure? = null,
    val topUp: TopUpState? = null,
    val paymentStarted: PaymentStarted? = null,
    val paymentOpenFailed: Boolean = false,
) : UiState {

    /**
     * Пополнять можно только когда баланс приехал: делитель единиц бэкенда
     * выводится из **этого же** ответа (issue #62), и без него неизвестно ни
     * сколько отправлять, ни какой минимум обещать. Заблокированный кошелёк
     * платежей не примет — предлагать заплатить и получить отказ незачем.
     */
    val canTopUp: Boolean
        get() = loadedWallet?.status?.let { it != WalletStatus.Blocked } == true

    /** Баланс, который уже приехал, — источник делителя единиц бэкенда. */
    val loadedWallet: Wallet? get() = (wallet as? ScreenState.Content)?.data
}

/**
 * Шторка пополнения.
 *
 * @param scale делитель единиц бэкенда из выдачи баланса — он же задаёт
 * минимум в сумах.
 * @param errors проверка черновика. Показываются только после первой попытки
 * отправки ([showErrors]): подсвечивать пустое поле сразу после открытия
 * шторки — ругать человека за то, что он ещё не начал.
 * @param failure отказ сервера. Остаётся в шторке рядом с набранной суммой:
 * закрыть её значило бы потерять и объяснение, и работу человека (issue #34).
 */
data class TopUpState(
    val draft: TopUpDraft = TopUpDraft(),
    val scale: Long,
    val isSubmitting: Boolean = false,
    val showErrors: Boolean = false,
    val errors: Set<TopUpError> = emptySet(),
    val failure: ApiFailure? = null,
) {
    val minAmountSum: Long get() = WalletTopUp.minAmountSum(scale)

    val visibleErrors: Set<TopUpError> get() = if (showErrors) errors else emptySet()
}

/** Что именно ушло в оплату — сумма нужна экрану после возврата. */
data class PaymentStarted(
    val amountSum: Long,
    val provider: TopUpProvider,
)

sealed interface WalletEvent : UiEvent {
    /** Экран вернулся на передний план: баланс мог измениться в другом месте. */
    data object ScreenResumed : WalletEvent

    data object Refreshed : WalletEvent
    data object Retry : WalletEvent
    data object TransactionsRetry : WalletEvent
    data object LoadMore : WalletEvent

    data object TopUpClicked : WalletEvent
    data object TopUpDismissed : WalletEvent
    data class TopUpAmountChanged(val value: String) : WalletEvent
    data class TopUpProviderSelected(val provider: TopUpProvider) : WalletEvent
    data object TopUpSubmitted : WalletEvent

    /** Форму оплаты открыть нечем: на устройстве нет браузера. */
    data object PaymentOpenFailed : WalletEvent

    data object PaymentNoticeDismissed : WalletEvent
}

sealed interface WalletEffect : UiEffect {
    /** Веб-форма провайдера. Ссылка уже проверена `PaymentLink`. */
    data class OpenPaymentForm(val url: String) : WalletEffect
}
