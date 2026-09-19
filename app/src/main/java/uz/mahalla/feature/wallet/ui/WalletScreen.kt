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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.format.DateTimeFormatters
import uz.mahalla.core.format.MoneyFormatter
import uz.mahalla.core.ui.components.CardSkeleton
import uz.mahalla.core.ui.components.ListSkeleton
import uz.mahalla.core.ui.components.LoadMoreAuto
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaDivider
import uz.mahalla.core.ui.components.MahallaErrorDetails
import uz.mahalla.core.ui.components.MahallaPullToRefresh
import uz.mahalla.core.ui.components.MahallaSegmentedControl
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.userMessage
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.feature.subscription.domain.ChargeProvider
import uz.mahalla.feature.subscription.domain.ChargeStatus
import uz.mahalla.feature.subscription.domain.Subscription
import uz.mahalla.feature.subscription.domain.SubscriptionStatus
import uz.mahalla.feature.wallet.domain.PaymentTransaction
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
 * Кошелёк: баланс и история операций (issue #62).
 *
 * До этого экран показывал одно и то же зашитое число всем — то есть врал про
 * деньги. Теперь и баланс, и история приходят с бэкенда, а когда не приходят,
 * экран говорит об этом словами сервера (issue #34).
 */
@Composable
fun WalletScreen(
    modifier: Modifier = Modifier,
    onOpenSubscription: () -> Unit = {},
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

                WalletEffect.OpenSubscription -> onOpenSubscription()
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
        // Мета шапки — «Mahalla+ до 12.10» (общая шапка макета): срок подписки
        // есть в состоянии ради карточки ниже, а здесь он на виду и без
        // прокрутки.
        val subscription = state.subscription
        MahallaTopBar(
            title = stringResource(R.string.wallet_title),
            brandMark = true,
            // В грейс-периоде «до 12.10» с прошедшей датой врало бы про срок —
            // тогда та же подпись, что у плашки карточки ниже: «истекает».
            meta = when {
                subscription == null -> null
                subscription.inGracePeriod -> stringResource(R.string.subscription_status_expiring)
                subscription.isActive && subscription.expiresAt != null -> stringResource(
                    R.string.wallet_header_subscription,
                    subscription.planName?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.subscription_plan_unnamed),
                    DateTimeFormatters.date(subscription.expiresAt),
                )
                else -> null
            },
        )
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
                // Карточка «Mahalla+» между кнопками и историей (макет 2c).
                // Нет подписки — нет и карточки: предлагать её отсюда некуда,
                // тарифы живут на своём экране в профиле.
                state.subscription?.let { subscription ->
                    item(key = "subscription") {
                        SubscriptionCard(
                            subscription = subscription,
                            onClick = { onEvent(WalletEvent.SubscriptionClicked) },
                        )
                    }
                }

                // Вкладка выбирает, что показывать: движения по счёту или
                // платежи PAYME/CLICK/UZUM (issue #184). Обе истории уже
                // загружены — переключение не ждёт сеть.
                item(key = "history-tabs") {
                    val selectedIndex = if (state.selectedTab == WalletTab.Payments) 1 else 0
                    MahallaSegmentedControl(
                        options = listOf(
                            stringResource(R.string.wallet_tab_transactions),
                            stringResource(R.string.wallet_tab_payments),
                        ),
                        selectedIndex = selectedIndex,
                        onSelect = { index ->
                            val tab = if (index == 1) WalletTab.Payments else WalletTab.Transactions
                            onEvent(WalletEvent.TabSelected(tab))
                        },
                    )
                }
                when (state.selectedTab) {
                    WalletTab.Transactions -> historyItems(state = state, onEvent = onEvent)
                    WalletTab.Payments -> paymentItems(state = state, onEvent = onEvent)
                }
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
            itemsIndexed(transactions.data, key = { _, it -> it.id }) { index, transaction ->
                Column {
                    if (index > 0) MahallaDivider()
                    TransactionCard(transaction = transaction)
                }
            }
            if (state.hasMore || state.loadMoreFailure != null) {
                item(key = "history-more") {
                    LoadMoreAuto(
                        itemCount = transactions.data.size,
                        isLoading = state.isLoadingMore,
                        failure = state.loadMoreFailure,
                        onLoadMore = { onEvent(WalletEvent.LoadMore) },
                    )
                }
            }
        }
    }
}

/**
 * Платежи PAYME/CLICK/UZUM (issue #184) — своя ручка и своя пагинация,
 * отдельная от [historyItems].
 */
private fun LazyListScope.paymentItems(
    state: WalletState,
    onEvent: (WalletEvent) -> Unit,
) {
    when (val payments = state.payments) {
        is ScreenState.Loading -> item(key = "payments-loading") {
            ListSkeleton(itemCount = HISTORY_SKELETONS)
        }

        // Пусто — это не ошибка: платежей могло не быть вовсе.
        is ScreenState.Empty -> item(key = "payments-empty") {
            Text(
                text = stringResource(R.string.wallet_payments_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMahallaColors.current.fgMuted,
            )
        }

        is ScreenState.Error -> item(key = "payments-error") {
            InlineFailure(
                failure = payments.failure,
                onRetry = { onEvent(WalletEvent.PaymentsRetry) },
            )
        }

        is ScreenState.Content -> {
            itemsIndexed(payments.data, key = { _, it -> it.id }) { index, payment ->
                Column {
                    if (index > 0) MahallaDivider()
                    PaymentCard(payment = payment)
                }
            }
            if (state.hasMorePayments || state.loadMorePaymentsFailure != null) {
                item(key = "payments-more") {
                    LoadMoreAuto(
                        itemCount = payments.data.size,
                        isLoading = state.isLoadingMorePayments,
                        failure = state.loadMorePaymentsFailure,
                        onLoadMore = { onEvent(WalletEvent.LoadMorePayments) },
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

/**
 * Баланс без карточки вокруг: в макете (2c) он лежит прямо на экране и
 * отделён от кнопок линией — карточка здесь добавила бы рамку, которой в
 * макете нет.
 */
@Composable
private fun BalanceCard(
    wallet: Wallet,
    canTopUp: Boolean,
    onTopUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currency = stringResource(R.string.currency_uzs)
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.wallet_available),
            style = MaterialTheme.typography.labelLarge,
            color = LocalMahallaColors.current.fgMuted,
        )
        // Сумма и валюта — разными кеглями: 40dp число читается с расстояния
        // вытянутой руки, «so'm» рядом ему в этом только мешал бы.
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
            MahallaDivider(modifier = Modifier.padding(top = Spacing.card))
            Box(modifier = Modifier.padding(top = Spacing.card)) {
                MahallaButton(
                    text = stringResource(R.string.wallet_top_up),
                    onClick = onTopUp,
                )
            }
            // Второй кнопки «Перевести» из макета здесь нет: ручки перевода у
            // бэкенда не существует (`docs/API-CONTRACT.md`, WalletApi — только
            // `wallet`, `wallet/transactions`, `wallet/top-up`), и кнопка вела
            // бы в никуда.
        }
    }
}

/**
 * Карточка подписки (макет 2c): название тарифа, срок и состояние.
 *
 * Тональная заливка `secondaryContainer` — та же, что у макетного `#e8deff`:
 * карточка должна отличаться и от фона экрана, и от строк истории под ней.
 *
 * Состояние подписки не пересчитывается на клиенте: у бэкенда есть
 * грейс-период и собственный счёт дней (`Subscription.daysRemaining`), и
 * вывод «активна» из даты окончания разошёлся бы с ним ровно в тот день,
 * когда это важнее всего.
 */
@Composable
private fun SubscriptionCard(
    subscription: Subscription,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MahallaCard(
        modifier = modifier,
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = subscription.planName?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.subscription_plan_unnamed),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                subscription.expiresAt?.let { expiresAt ->
                    Text(
                        text = stringResource(
                            R.string.text_joined_with_dot,
                            stringResource(R.string.subscription_expires),
                            DateTimeFormatters.date(expiresAt),
                        ),
                        style = MaterialTheme.typography.labelLarge.merge(TabularNums),
                        color = LocalMahallaColors.current.fgMuted,
                    )
                }
            }
            MahallaBadge(
                text = stringResource(subscription.badgeRes()),
                tone = if (subscription.isActive) MahallaTone.Accent else MahallaTone.Neutral,
            )
        }
    }
}

/**
 * Подпись состояния на карточке. Пробный период важнее статуса: «активна» у
 * пробной подписки скрывает, что она закончится сама.
 */
private fun Subscription.badgeRes(): Int = when {
    isTrial -> R.string.subscription_trial_badge
    inGracePeriod -> R.string.subscription_status_expiring
    else -> when (status) {
        SubscriptionStatus.Active -> R.string.subscription_status_active
        SubscriptionStatus.Expired -> R.string.subscription_status_expired
        SubscriptionStatus.Cancelled -> R.string.subscription_status_cancelled
        SubscriptionStatus.Unknown -> R.string.subscription_status_unknown
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
 *
 * Ряд с линией снизу, а не карточка на каждую операцию: так в макете (2c), и
 * на длинной истории стопка карточек читается хуже простого списка.
 */
@Composable
private fun TransactionCard(transaction: WalletTransaction, modifier: Modifier = Modifier) {
    val colors = LocalMahallaColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.item)
            .semantics(mergeDescendants = true) {},
    ) {
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
                // Приход выделен цветом, списание — обычным текстом. Цвет
                // прихода в редизайне фиолетовый (onSecondaryContainer), а не
                // зелёный: в макете 2c «+300 000» подписано #4a2f9e, и знак «+»
                // рядом остаётся вторым признаком, не завязанным на цвет.
                color = if (transaction.direction == TransactionDirection.In) {
                    MaterialTheme.colorScheme.onSecondaryContainer
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
 * Строка платежа PAYME/CLICK/UZUM (issue #184): провайдер, сумма, статус и
 * причина отказа. В отличие от [TransactionCard] сумма без знака — это не
 * движение по счёту с направлением, а сам платёж.
 */
@Composable
private fun PaymentCard(payment: PaymentTransaction, modifier: Modifier = Modifier) {
    val colors = LocalMahallaColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.item)
            .semantics(mergeDescendants = true) {},
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = payment.provider.titleRes()?.let { stringResource(it) }
                        ?: stringResource(R.string.wallet_payment_default_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                payment.createdAt?.let { createdAt ->
                    Text(
                        text = DateTimeFormatters.dateTime(createdAt),
                        style = MaterialTheme.typography.labelLarge.merge(TabularNums),
                        color = colors.fgMuted,
                    )
                }
            }
            Text(
                text = MoneyFormatter.amount(payment.amountSum),
                style = MaterialTheme.typography.titleMedium.merge(TabularNums),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        // Статус показывается бейджем только там, где он не «всё в порядке» —
        // успешный платёж бейджем не отмечен, как и завершённая операция
        // кошелька.
        val statusLabel = payment.status.badgeRes()
        if (statusLabel != null) {
            Row(modifier = Modifier.padding(top = Spacing.item)) {
                MahallaBadge(
                    text = stringResource(statusLabel),
                    tone = if (payment.status == ChargeStatus.Failed) {
                        MahallaTone.Error
                    } else {
                        MahallaTone.Warning
                    },
                )
            }
        }
        // Причина отказа — текст сервера как есть, свой придумать нельзя.
        payment.errorMessage?.let { errorMessage ->
            Text(
                text = errorMessage,
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Провайдер незнакомого значения ([ChargeProvider.Unknown]) не подписан. */
private fun ChargeProvider.titleRes(): Int? = when (this) {
    ChargeProvider.Payme -> R.string.wallet_top_up_provider_payme
    ChargeProvider.Click -> R.string.wallet_top_up_provider_click
    ChargeProvider.Uzum -> R.string.wallet_top_up_provider_uzum
    ChargeProvider.Cash -> R.string.checkout_payment_cash
    ChargeProvider.Unknown -> null
}

/**
 * Бейдж статуса. `Paid` бейджем не отмечен — это ожидаемый исход платежа, не
 * то, ради чего в историю заходят.
 */
private fun ChargeStatus.badgeRes(): Int? = when (this) {
    ChargeStatus.Pending -> R.string.wallet_transaction_pending
    ChargeStatus.Failed -> R.string.wallet_transaction_failed
    ChargeStatus.Cancelled -> R.string.wallet_payment_status_cancelled
    ChargeStatus.Refunded -> R.string.wallet_payment_status_refunded
    ChargeStatus.Paid, ChargeStatus.Unknown -> null
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

private const val HISTORY_SKELETONS = 3

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
