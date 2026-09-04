package uz.mahalla.feature.wallet.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.format.DateTimeFormatters
import uz.mahalla.core.format.MoneyFormatter
import uz.mahalla.core.ui.components.CardSkeleton
import uz.mahalla.core.ui.components.ListSkeleton
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaErrorDetails
import uz.mahalla.core.ui.components.MahallaPullToRefresh
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.SectionHeader
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.userMessage
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.feature.wallet.domain.TransactionDirection
import uz.mahalla.feature.wallet.domain.TransactionStatus
import uz.mahalla.feature.wallet.domain.Wallet
import uz.mahalla.feature.wallet.domain.WalletStatus
import uz.mahalla.feature.wallet.domain.WalletTransaction
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums
import java.time.Instant

/**
 * Кошелёк: баланс и история операций (issue #62).
 *
 * До этого экран показывал одно и то же зашитое число всем — то есть врал про
 * деньги. Теперь и баланс, и история приходят с бэкенда, а когда не приходят,
 * экран говорит об этом словами сервера (issue #34).
 */
@Composable
fun WalletScreen(
    modifier: Modifier = Modifier,
    viewModel: WalletViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Баланс мог измениться, пока приложение было в фоне: заказ оплачен,
    // пополнение дошло. Показывать вчерашние деньги нельзя — и это же
    // перечитывает баланс после возврата из формы оплаты (issue #93).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(WalletEvent.ScreenResumed)
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is WalletEffect.OpenPaymentForm ->
                    if (!context.openPaymentForm(effect.url)) {
                        viewModel.onEvent(WalletEvent.PaymentOpenFailed)
                    }
            }
        }
    }

    WalletContentScreen(state = state, onEvent = viewModel::onEvent, modifier = modifier)
}

/**
 * Форма оплаты открывается тем, что есть на устройстве: своего Custom Tab в
 * проекте нет (`androidx.browser` не подключён), а веб-форма провайдера — это
 * обычная https-страница. Ссылка уже проверена
 * [uz.mahalla.feature.wallet.domain.PaymentLink].
 *
 * Тап без последствий читается как сломанная кнопка, поэтому отсутствие
 * браузера возвращается наверх и объясняется словами — как открытие магазина в
 * issue #80.
 */
private fun Context.openPaymentForm(url: String): Boolean = runCatching {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}.onFailure { if (it !is ActivityNotFoundException) throw it }.isSuccess

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun WalletContentScreen(
    state: WalletState,
    onEvent: (WalletEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(title = stringResource(R.string.wallet_title))
        MahallaPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(WalletEvent.Refreshed) },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.gap),
            ) {
                item(key = "balance") {
                    BalanceBlock(
                        state = state.wallet,
                        canTopUp = state.canTopUp,
                        onEvent = onEvent,
                    )
                }
                // Платёж ушёл в форму провайдера и человек вернулся: пока
                // колбэк не дошёл, баланс тот же — и молчание здесь читается
                // как потерянные деньги.
                if (state.paymentStarted != null || state.paymentOpenFailed) {
                    item(key = "payment-notice") {
                        PaymentNotice(
                            started = state.paymentStarted,
                            openFailed = state.paymentOpenFailed,
                            onDismiss = { onEvent(WalletEvent.PaymentNoticeDismissed) },
                        )
                    }
                }
                item(key = "history-header") {
                    SectionHeader(title = stringResource(R.string.wallet_history_title))
                }
                historyItems(state = state, onEvent = onEvent)
            }
        }
    }

    state.topUp?.let { topUp ->
        TopUpSheet(state = topUp, onEvent = onEvent)
    }
}

/**
 * История. Состояния разложены руками, а не через `ScreenStateHost`: тот
 * рисует `ApiErrorState` с собственной прокруткой, а внутри `LazyColumn`
 * вложенная прокрутка меряется бесконечной высотой и роняет измерение.
 */
private fun LazyListScope.historyItems(
    state: WalletState,
    onEvent: (WalletEvent) -> Unit,
) {
    when (val transactions = state.transactions) {
        is ScreenState.Loading -> item(key = "history-loading") {
            ListSkeleton(itemCount = HISTORY_SKELETONS)
        }

        // Пусто — это не ошибка: у нового кошелька операций и не было.
        is ScreenState.Empty -> item(key = "history-empty") {
            Text(
                text = stringResource(R.string.wallet_history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMahallaColors.current.fgMuted,
            )
        }

        is ScreenState.Error -> item(key = "history-error") {
            InlineFailure(
                failure = transactions.failure,
                onRetry = { onEvent(WalletEvent.TransactionsRetry) },
            )
        }

        is ScreenState.Content -> {
            items(transactions.data, key = WalletTransaction::id) { transaction ->
                TransactionCard(transaction = transaction)
            }
            if (state.hasMore || state.loadMoreFailure != null) {
                item(key = "history-more") {
                    LoadMoreItem(
                        state = state,
                        itemCount = transactions.data.size,
                        onEvent = onEvent,
                    )
                }
            }
        }
    }
}

@Composable
private fun BalanceBlock(
    state: ScreenState<Wallet>,
    canTopUp: Boolean,
    onEvent: (WalletEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is ScreenState.Loading -> CardSkeleton(modifier = modifier)

        // Кошелька без операций не бывает пустым состоянием: ноль — это тоже
        // ответ, и он приезжает как Content.
        is ScreenState.Empty -> CardSkeleton(modifier = modifier)

        is ScreenState.Error -> MahallaCard(modifier = modifier) {
            InlineFailure(failure = state.failure, onRetry = { onEvent(WalletEvent.Retry) })
        }

        is ScreenState.Content -> BalanceCard(
            wallet = state.data,
            canTopUp = canTopUp,
            onTopUp = { onEvent(WalletEvent.TopUpClicked) },
            modifier = modifier,
        )
    }
}

@Composable
private fun BalanceCard(
    wallet: Wallet,
    canTopUp: Boolean,
    onTopUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currency = stringResource(R.string.currency_uzs)
    MahallaCard(modifier = modifier) {
        Text(
            text = stringResource(R.string.wallet_available),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalMahallaColors.current.fgMuted,
        )
        Text(
            text = MoneyFormatter.withCurrency(wallet.availableSum, currency),
            style = MaterialTheme.typography.displaySmall.merge(TabularNums),
            color = MaterialTheme.colorScheme.onSurface,
        )
        // Строки показываются только когда есть что показывать: у обычного
        // кошелька ни заморозки, ни бонусов нет, и три нуля подряд ничего не
        // объясняют.
        if (wallet.heldSum > 0) {
            AmountRow(
                label = stringResource(R.string.wallet_balance_total),
                value = MoneyFormatter.withCurrency(wallet.balanceSum, currency),
            )
            AmountRow(
                label = stringResource(R.string.wallet_held),
                value = MoneyFormatter.withCurrency(wallet.heldSum, currency),
            )
        }
        if (wallet.bonusSum > 0) {
            AmountRow(
                label = stringResource(R.string.wallet_bonus),
                value = MoneyFormatter.withCurrency(wallet.bonusSum, currency),
            )
        }
        if (wallet.status == WalletStatus.Blocked) {
            Box(modifier = Modifier.padding(top = Spacing.item)) {
                MahallaBadge(
                    text = stringResource(R.string.wallet_status_blocked),
                    tone = MahallaTone.Error,
                )
            }
        }
        // Заблокированному кошельку платёж всё равно откажут — предлагать
        // заплатить и получить отказ незачем.
        if (canTopUp) {
            Box(modifier = Modifier.padding(top = Spacing.gap)) {
                MahallaButton(
                    text = stringResource(R.string.wallet_top_up),
                    onClick = onTopUp,
                )
            }
        }
    }
}

@Composable
private fun AmountRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.item),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalMahallaColors.current.fgMuted,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Строка истории. Сумма со знаком и цветом направления — по ней человек
 * отличает пополнение от списания, не читая подписи.
 */
@Composable
private fun TransactionCard(transaction: WalletTransaction, modifier: Modifier = Modifier) {
    val colors = LocalMahallaColors.current
    MahallaCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = transaction.description
                        ?: transaction.type
                        ?: stringResource(R.string.wallet_transaction_default_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                transaction.createdAt?.let { createdAt ->
                    Text(
                        text = DateTimeFormatters.dateTime(createdAt),
                        style = MaterialTheme.typography.bodySmall.merge(TabularNums),
                        color = colors.fgMuted,
                    )
                }
            }
            Text(
                text = MoneyFormatter.signedAmount(transaction.signedAmountSum),
                style = MaterialTheme.typography.titleMedium.merge(TabularNums),
                // Зелёным выделяется только приход: списание — обычное
                // событие, красить его тревожным цветом незачем.
                color = if (transaction.direction == TransactionDirection.In) {
                    colors.success
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
        // Незавершённая и неудавшаяся операции — то, ради чего в историю и
        // заходят: деньги ушли, а результата нет.
        val statusLabel = when (transaction.status) {
            TransactionStatus.Pending -> stringResource(R.string.wallet_transaction_pending)
            TransactionStatus.Failed -> stringResource(R.string.wallet_transaction_failed)
            TransactionStatus.Completed, TransactionStatus.Unknown -> null
        }
        if (statusLabel != null || transaction.isBonus) {
            Row(
                modifier = Modifier.padding(top = Spacing.item),
                horizontalArrangement = Arrangement.spacedBy(Spacing.item),
            ) {
                statusLabel?.let {
                    MahallaBadge(
                        text = it,
                        tone = if (transaction.status == TransactionStatus.Failed) {
                            MahallaTone.Error
                        } else {
                            MahallaTone.Warning
                        },
                    )
                }
                if (transaction.isBonus) {
                    MahallaBadge(
                        text = stringResource(R.string.wallet_transaction_bonus),
                        tone = MahallaTone.Accent,
                    )
                }
            }
        }
    }
}

/**
 * Отказ внутри списка: текст сервера, подробности и повтор. `ApiErrorState`
 * здесь не годится — он прокручивается сам (см. [historyItems]).
 */
@Composable
private fun InlineFailure(
    failure: ApiFailure,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        Text(
            text = failure.userMessage(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        failure.server?.let { MahallaErrorDetails(server = it) }
        MahallaButton(
            text = stringResource(R.string.action_retry),
            onClick = onRetry,
            variant = MahallaButtonVariant.Secondary,
            fillWidth = false,
        )
    }
}

/**
 * Хвост истории: догрузка следующей страницы по достижению конца списка.
 * Провал показывает кнопку с причиной — автотриггер по `itemCount` больше не
 * сработает, список ведь не вырос.
 */
@Composable
private fun LoadMoreItem(
    state: WalletState,
    itemCount: Int,
    onEvent: (WalletEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val failure = state.loadMoreFailure
    if (failure != null) {
        InlineFailure(
            failure = failure,
            onRetry = { onEvent(WalletEvent.LoadMore) },
            modifier = modifier,
        )
        return
    }

    LaunchedEffect(itemCount) { onEvent(WalletEvent.LoadMore) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.gap),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(LOAD_MORE_INDICATOR))
    }
}

private const val HISTORY_SKELETONS = 3
private val LOAD_MORE_INDICATOR = 24.dp

@ThemeLanguagePreviews
@Composable
private fun WalletScreenPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        WalletContentScreen(
            state = WalletState(
                wallet = ScreenState.Content(
                    Wallet(
                        balanceSum = 1_284_500,
                        bonusSum = 15_000,
                        heldSum = 84_500,
                        availableSum = 1_200_000,
                        status = WalletStatus.Active,
                    ),
                ),
                transactions = ScreenState.Content(
                    listOf(
                        WalletTransaction(
                            id = "t-1",
                            description = "Payme orqali to'ldirish",
                            direction = TransactionDirection.In,
                            amountSum = 500_000,
                            signedAmountSum = 500_000,
                            status = TransactionStatus.Completed,
                            createdAt = Instant.parse("2026-08-29T12:30:00Z"),
                        ),
                        WalletTransaction(
                            id = "t-2",
                            description = "Osh Markazi",
                            direction = TransactionDirection.Out,
                            amountSum = 84_500,
                            signedAmountSum = -84_500,
                            status = TransactionStatus.Pending,
                            createdAt = Instant.parse("2026-08-30T07:05:00Z"),
                        ),
                    ),
                ),
            ),
            onEvent = {},
        )
    }
}
