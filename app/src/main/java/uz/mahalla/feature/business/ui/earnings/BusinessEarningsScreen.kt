package uz.mahalla.feature.business.ui.earnings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.format.DateTimeFormatters
import uz.mahalla.core.format.MoneyFormatter
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.CardSkeleton
import uz.mahalla.core.ui.components.ListSkeleton
import uz.mahalla.core.ui.components.LoadMoreAuto
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaBottomSheet
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaDivider
import uz.mahalla.core.ui.components.MahallaErrorDetails
import uz.mahalla.core.ui.components.MahallaPullToRefresh
import uz.mahalla.core.ui.components.MahallaTextField
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.userMessage
import uz.mahalla.feature.business.domain.Payout
import uz.mahalla.feature.business.domain.PayoutError
import uz.mahalla.feature.business.domain.PayoutStatus
import uz.mahalla.feature.wallet.domain.TransactionDirection
import uz.mahalla.feature.wallet.domain.TransactionStatus
import uz.mahalla.feature.wallet.domain.Wallet
import uz.mahalla.feature.wallet.domain.WalletStatus
import uz.mahalla.feature.wallet.domain.WalletTransaction
import uz.mahalla.ui.theme.FocusDisplayBalance
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums
import java.time.Instant

/**
 * «Заработок» (issue #290): баланс бизнес-кошелька, история начислений и
 * заявка на вывод.
 *
 * Комиссия и возврат отдельной вёрсткой не выделены: контракт не описывает
 * для них отдельных полей (`docs/API-CONTRACT.md`), это обычные строки
 * истории — начисление и удержанная с него комиссия приезжают двумя
 * записями, возврат — записью с противоположным направлением.
 */
@Composable
fun BusinessEarningsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BusinessEarningsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(BusinessEarningsEvent.ScreenResumed)
    }

    BusinessEarningsContentScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun BusinessEarningsContentScreen(
    state: BusinessEarningsState,
    onEvent: (BusinessEarningsEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(
            title = stringResource(R.string.business_section_earnings),
            onBack = onBack,
        )
        MahallaPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(BusinessEarningsEvent.Refreshed) },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.gap),
            ) {
                item(key = "balance") {
                    BalanceBlock(
                        state = state.wallet,
                        canRequestPayout = state.canRequestPayout,
                        onEvent = onEvent,
                    )
                }
                state.lastPayout?.let { payout ->
                    item(key = "last-payout") {
                        LastPayoutCard(
                            payout = payout,
                            onDismiss = { onEvent(BusinessEarningsEvent.LastPayoutDismissed) },
                        )
                    }
                }
                historyItems(state = state, onEvent = onEvent)
            }
        }
    }

    state.payout?.let { payout ->
        PayoutSheet(state = payout, onEvent = onEvent)
    }
}

/**
 * История. Состояния разложены руками, а не через `ScreenStateHost` — тот
 * рисует `ApiErrorState` со своей прокруткой, а вложенная прокрутка внутри
 * `LazyColumn` роняет измерение (issue #62), та же причина, что и у личного
 * кошелька.
 */
private fun LazyListScope.historyItems(
    state: BusinessEarningsState,
    onEvent: (BusinessEarningsEvent) -> Unit,
) {
    when (val transactions = state.transactions) {
        is ScreenState.Loading -> item(key = "history-loading") {
            ListSkeleton(itemCount = HISTORY_SKELETONS)
        }

        is ScreenState.Empty -> item(key = "history-empty") {
            Text(
                text = stringResource(R.string.business_earnings_history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMahallaColors.current.fgMuted,
            )
        }

        is ScreenState.Error -> item(key = "history-error") {
            InlineFailure(
                failure = transactions.failure,
                onRetry = { onEvent(BusinessEarningsEvent.TransactionsRetry) },
            )
        }

        is ScreenState.Content -> {
            itemsIndexed(transactions.data, key = { _, it -> it.id }) { index, transaction ->
                Column {
                    if (index > 0) MahallaDivider()
                    EarningsTransactionRow(transaction = transaction)
                }
            }
            if (state.hasMore || state.loadMoreFailure != null) {
                item(key = "history-more") {
                    LoadMoreAuto(
                        itemCount = transactions.data.size,
                        isLoading = state.isLoadingMore,
                        failure = state.loadMoreFailure,
                        onLoadMore = { onEvent(BusinessEarningsEvent.LoadMore) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BalanceBlock(
    state: ScreenState<Wallet>,
    canRequestPayout: Boolean,
    onEvent: (BusinessEarningsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is ScreenState.Loading -> CardSkeleton(modifier = modifier)
        is ScreenState.Empty -> CardSkeleton(modifier = modifier)

        is ScreenState.Error -> MahallaCard(modifier = modifier) {
            InlineFailure(failure = state.failure, onRetry = { onEvent(BusinessEarningsEvent.Retry) })
        }

        is ScreenState.Content -> BalanceCard(
            wallet = state.data,
            canRequestPayout = canRequestPayout,
            onPayout = { onEvent(BusinessEarningsEvent.PayoutClicked) },
            modifier = modifier,
        )
    }
}

@Composable
private fun BalanceCard(
    wallet: Wallet,
    canRequestPayout: Boolean,
    onPayout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currency = stringResource(R.string.currency_uzs)
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.business_earnings_available),
            style = MaterialTheme.typography.labelLarge,
            color = LocalMahallaColors.current.fgMuted,
        )
        Row(
            modifier = Modifier.padding(top = Spacing.item / 2),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item / 2),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = MoneyFormatter.amount(wallet.availableSum),
                style = FocusDisplayBalance.merge(TabularNums),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = currency,
                modifier = Modifier.padding(bottom = Spacing.item / 2),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMahallaColors.current.fgMuted,
            )
        }
        // Заморозка показывается только когда есть что показывать — как и у
        // личного кошелька: три нуля подряд ничего не объясняют.
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
        if (wallet.status == WalletStatus.Blocked) {
            Box(modifier = Modifier.padding(top = Spacing.item)) {
                MahallaBadge(
                    text = stringResource(R.string.wallet_status_blocked),
                    tone = MahallaTone.Error,
                )
            }
        }
        if (canRequestPayout) {
            MahallaDivider(modifier = Modifier.padding(top = Spacing.card))
            Box(modifier = Modifier.padding(top = Spacing.card)) {
                MahallaButton(
                    text = stringResource(R.string.business_earnings_payout),
                    onClick = onPayout,
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
 * Заявка, отправленная только что. Своей ручки статуса у бэкенда нет
 * (`docs/API-CONTRACT.md`) — карточка показывает то, что вернул сам запрос на
 * создание, и живёт до тех пор, пока человек её не закроет или не откроет
 * экран заново.
 */
@Composable
private fun LastPayoutCard(payout: Payout, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val currency = stringResource(R.string.currency_uzs)
    MahallaCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.business_earnings_payout_sent),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    payout.cardMasked?.let { masked ->
                        Text(
                            text = masked,
                            style = MaterialTheme.typography.labelLarge.merge(TabularNums),
                            color = LocalMahallaColors.current.fgMuted,
                        )
                    }
                }
                Text(
                    text = MoneyFormatter.withCurrency(payout.amountSum, currency),
                    style = MaterialTheme.typography.titleMedium.merge(TabularNums),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            MahallaBadge(
                text = stringResource(payout.status.labelRes()),
                tone = payout.status.tone(),
            )
            MahallaButton(
                text = stringResource(R.string.wallet_top_up_started_dismiss),
                onClick = onDismiss,
                variant = MahallaButtonVariant.Ghost,
                fillWidth = false,
            )
        }
    }
}

@StringRes
private fun PayoutStatus.labelRes(): Int = when (this) {
    PayoutStatus.Pending -> R.string.business_earnings_payout_status_pending
    PayoutStatus.Approved -> R.string.business_earnings_payout_status_approved
    PayoutStatus.Completed -> R.string.business_earnings_payout_status_completed
    PayoutStatus.Rejected -> R.string.business_earnings_payout_status_rejected
    PayoutStatus.Unknown -> R.string.business_earnings_payout_status_pending
}

private fun PayoutStatus.tone(): MahallaTone = when (this) {
    PayoutStatus.Completed -> MahallaTone.Success
    PayoutStatus.Rejected -> MahallaTone.Error
    PayoutStatus.Pending, PayoutStatus.Approved, PayoutStatus.Unknown -> MahallaTone.Warning
}

/**
 * Строка истории. Начисление, комиссия и возврат — обычные записи с разным
 * направлением и суммой, отдельной вёрстки для них нет (issue #290): сервер
 * не выделяет их отдельными полями, а придумывать разбор поверх обычной
 * истории значило бы полагаться на угаданную схему.
 */
@Composable
private fun EarningsTransactionRow(transaction: WalletTransaction, modifier: Modifier = Modifier) {
    val colors = LocalMahallaColors.current
    Column(modifier = modifier.fillMaxWidth().padding(vertical = Spacing.item)) {
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
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                transaction.createdAt?.let { createdAt ->
                    Text(
                        text = DateTimeFormatters.dateTime(createdAt),
                        style = MaterialTheme.typography.labelLarge.merge(TabularNums),
                        color = colors.fgMuted,
                    )
                }
            }
            Text(
                text = MoneyFormatter.signedAmount(transaction.signedAmountSum),
                style = MaterialTheme.typography.titleMedium.merge(TabularNums),
                color = if (transaction.direction == TransactionDirection.In) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
        val statusLabel = when (transaction.status) {
            TransactionStatus.Pending -> stringResource(R.string.wallet_transaction_pending)
            TransactionStatus.Failed -> stringResource(R.string.wallet_transaction_failed)
            TransactionStatus.Completed, TransactionStatus.Unknown -> null
        }
        statusLabel?.let {
            Row(modifier = Modifier.padding(top = Spacing.item)) {
                MahallaBadge(
                    text = it,
                    tone = if (transaction.status == TransactionStatus.Failed) {
                        MahallaTone.Error
                    } else {
                        MahallaTone.Warning
                    },
                )
            }
        }
    }
}

/**
 * Шторка заявки на вывод. Минимальной суммы и комиссии в подсказках нет
 * намеренно: контракт их не называет (issue #290), а отказ по любой из этих
 * причин покажет текст сервера — `state.failure` ниже.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PayoutSheet(
    state: PayoutSheetState,
    onEvent: (BusinessEarningsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val errors = state.visibleErrors
    MahallaBottomSheet(
        onDismiss = { onEvent(BusinessEarningsEvent.PayoutDismissed) },
        modifier = modifier,
        title = stringResource(R.string.business_earnings_payout_title),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.gap)) {
            MahallaTextField(
                value = state.draft.amountText,
                onValueChange = { onEvent(BusinessEarningsEvent.PayoutAmountChanged(it)) },
                label = stringResource(R.string.business_earnings_payout_amount),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                ),
                errorText = if (PayoutError.AmountRequired in errors) {
                    stringResource(R.string.business_earnings_payout_error_amount)
                } else {
                    null
                },
                enabled = !state.isSubmitting,
            )
            MahallaTextField(
                value = state.draft.cardNumberText,
                onValueChange = { onEvent(BusinessEarningsEvent.PayoutCardNumberChanged(it)) },
                label = stringResource(R.string.business_earnings_payout_card),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                errorText = if (PayoutError.CardNumberInvalid in errors) {
                    stringResource(R.string.business_earnings_payout_error_card)
                } else {
                    null
                },
                enabled = !state.isSubmitting,
            )

            // Отказ остаётся в шторке рядом с набранной суммой — та же причина,
            // что у пополнения личного кошелька (issue #34): закрыть шторку
            // значило бы потерять и объяснение, и работу человека.
            state.failure?.let { failure ->
                Text(
                    text = failure.userMessage(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                failure.server?.let { MahallaErrorDetails(server = it) }
            }

            MahallaButton(
                text = stringResource(R.string.business_earnings_payout_submit),
                onClick = { onEvent(BusinessEarningsEvent.PayoutSubmitted) },
                modifier = Modifier.fillMaxWidth(),
                state = if (state.isSubmitting) ButtonState.Loading else ButtonState.Default,
            )
        }
    }
}

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

private const val HISTORY_SKELETONS = 3

@ThemeLanguagePreviews
@Composable
private fun BusinessEarningsScreenPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        BusinessEarningsContentScreen(
            state = BusinessEarningsState(
                wallet = ScreenState.Content(
                    Wallet(
                        balanceSum = 2_450_000,
                        availableSum = 2_450_000,
                    ),
                ),
                transactions = ScreenState.Content(
                    listOf(
                        WalletTransaction(
                            id = "e-1",
                            description = "Buyurtma #A-042 uchun to'lov",
                            direction = TransactionDirection.In,
                            amountSum = 185_000,
                            signedAmountSum = 185_000,
                            status = TransactionStatus.Completed,
                            createdAt = Instant.parse("2026-09-20T10:00:00Z"),
                        ),
                        WalletTransaction(
                            id = "e-2",
                            description = "Xizmat haqi (komissiya)",
                            direction = TransactionDirection.Out,
                            amountSum = 18_500,
                            signedAmountSum = -18_500,
                            status = TransactionStatus.Completed,
                            createdAt = Instant.parse("2026-09-20T10:00:00Z"),
                        ),
                        WalletTransaction(
                            id = "e-3",
                            description = "Buyurtma #A-039 qaytarildi",
                            direction = TransactionDirection.Out,
                            amountSum = 95_000,
                            signedAmountSum = -95_000,
                            status = TransactionStatus.Completed,
                            createdAt = Instant.parse("2026-09-19T15:00:00Z"),
                        ),
                    ),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
