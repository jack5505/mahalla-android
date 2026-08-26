package uz.mahalla.feature.food.ui.checkout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.format.MoneyFormatter
import uz.mahalla.core.ui.messageRes
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.FilterChipUi
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaFilterRow
import uz.mahalla.core.ui.components.MahallaSegmentedControl
import uz.mahalla.core.ui.components.MahallaSwitchRow
import uz.mahalla.core.ui.components.MahallaTextField
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.SectionHeader
import uz.mahalla.feature.food.domain.CheckoutError
import uz.mahalla.feature.food.domain.DeliveryMethod
import uz.mahalla.feature.food.domain.PaymentMethod
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Оформление заказа (эпик 5.3): доставка или самовывоз, адрес, время, оплата,
 * комментарий и итог.
 */
@Composable
fun CheckoutScreen(
    onOrderCreated: (String) -> Unit,
    onOpenWallet: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CheckoutViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is CheckoutEffect.OrderCreated -> onOrderCreated(effect.orderId)
                CheckoutEffect.OpenWallet -> onOpenWallet()
                CheckoutEffect.NavigateBack -> onBack()
            }
        }
    }

    CheckoutContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun CheckoutContent(
    state: CheckoutState,
    onEvent: (CheckoutEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currency = stringResource(R.string.currency_uzs)
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(title = stringResource(R.string.checkout_title), onBack = onBack)

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .imePadding()
                .padding(horizontal = Spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.gap),
            contentPadding = PaddingValues(vertical = Spacing.gap),
        ) {
            item(key = "method") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
                    SectionHeader(title = stringResource(R.string.checkout_method))
                    MahallaSegmentedControl(
                        options = listOf(
                            stringResource(R.string.checkout_method_delivery),
                            stringResource(R.string.checkout_method_pickup),
                        ),
                        selectedIndex = if (state.form.method == DeliveryMethod.Pickup) 1 else 0,
                        onSelect = { index ->
                            onEvent(
                                CheckoutEvent.MethodSelected(
                                    if (index == 1) DeliveryMethod.Pickup else DeliveryMethod.Delivery,
                                ),
                            )
                        },
                    )
                }
            }

            if (state.form.needsAddress) {
                item(key = "address") {
                    MahallaTextField(
                        value = state.form.address,
                        onValueChange = { onEvent(CheckoutEvent.AddressChanged(it)) },
                        label = stringResource(R.string.checkout_address),
                        placeholder = stringResource(R.string.checkout_address_placeholder),
                        errorText = state.error { it is CheckoutError.AddressRequired }
                            ?.let { stringResource(R.string.checkout_error_address) },
                        singleLine = false,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                }
            }

            item(key = "time") {
                TimeBlock(state = state, onEvent = onEvent)
            }

            item(key = "payment") {
                PaymentBlock(state = state, onEvent = onEvent)
            }

            item(key = "comment") {
                MahallaTextField(
                    value = state.form.comment,
                    onValueChange = { onEvent(CheckoutEvent.CommentChanged(it)) },
                    label = stringResource(R.string.checkout_comment),
                    placeholder = stringResource(R.string.checkout_comment_placeholder),
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }

            if (state.submitError != null) {
                item(key = "submit-error") {
                    Text(
                        text = stringResource(state.submitError.messageRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        SubmitBar(state = state, currency = currency, onEvent = onEvent)
    }
}

@Composable
private fun TimeBlock(
    state: CheckoutState,
    onEvent: (CheckoutEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
        SectionHeader(title = stringResource(R.string.checkout_time))
        MahallaSwitchRow(
            title = stringResource(R.string.checkout_time_asap),
            checked = state.form.asap,
            onCheckedChange = { onEvent(CheckoutEvent.AsapToggled(it)) },
        )
        if (!state.form.asap) {
            MahallaFilterRow(
                items = state.slots.map { slot ->
                    FilterChipUi(id = slot.slotId(), label = slot.format(SLOT_FORMATTER))
                },
                selectedId = state.form.scheduledAt?.slotId(),
                onSelect = { id ->
                    state.slots.firstOrNull { it.slotId() == id }
                        ?.let { onEvent(CheckoutEvent.SlotSelected(it)) }
                },
            )
            val timeError = state.error {
                it is CheckoutError.TimeRequired || it is CheckoutError.TimeTooSoon
            }
            if (timeError != null) {
                Text(
                    text = when (timeError) {
                        is CheckoutError.TimeTooSoon -> stringResource(
                            R.string.checkout_error_time_too_soon,
                            timeError.minLeadMinutes,
                        )

                        else -> stringResource(R.string.checkout_error_time_required)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PaymentBlock(
    state: CheckoutState,
    onEvent: (CheckoutEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currency = stringResource(R.string.currency_uzs)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
        SectionHeader(title = stringResource(R.string.checkout_payment))
        MahallaSegmentedControl(
            options = listOf(
                stringResource(R.string.checkout_payment_wallet),
                stringResource(R.string.checkout_payment_cash),
            ),
            selectedIndex = if (state.form.payment == PaymentMethod.Cash) 1 else 0,
            onSelect = { index ->
                onEvent(
                    CheckoutEvent.PaymentSelected(
                        if (index == 1) PaymentMethod.Cash else PaymentMethod.Wallet,
                    ),
                )
            },
        )
        if (state.form.payment == PaymentMethod.Wallet && state.balanceKnown) {
            Text(
                text = stringResource(
                    R.string.checkout_wallet_balance,
                    MoneyFormatter.withCurrency(state.walletBalanceSum, currency),
                ),
                style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
                color = LocalMahallaColors.current.fgMuted,
            )
        }
        val missing = state.insufficientFunds
        if (missing != null && state.validationShown) {
            Text(
                text = stringResource(
                    R.string.checkout_error_insufficient_funds,
                    MoneyFormatter.withCurrency(missing.missingSum, currency),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            MahallaButton(
                text = stringResource(R.string.checkout_top_up),
                onClick = { onEvent(CheckoutEvent.TopUpClicked) },
                variant = MahallaButtonVariant.Secondary,
                fillWidth = false,
            )
        }
    }
}

@Composable
private fun SubmitBar(
    state: CheckoutState,
    currency: String,
    onEvent: (CheckoutEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.gutter, vertical = Spacing.gap),
            verticalArrangement = Arrangement.spacedBy(Spacing.item),
        ) {
            if (state.totals.deliverySum > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.checkout_delivery_fee),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalMahallaColors.current.fgMuted,
                    )
                    Text(
                        text = MoneyFormatter.withCurrency(state.totals.deliverySum, currency),
                        style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
                        color = LocalMahallaColors.current.fgMuted,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.cart_total),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = MoneyFormatter.withCurrency(state.totals.totalSum, currency),
                    style = MaterialTheme.typography.titleMedium.merge(TabularNums),
                )
            }
            MahallaButton(
                text = stringResource(R.string.checkout_submit),
                onClick = { onEvent(CheckoutEvent.SubmitClicked) },
                state = ButtonState(enabled = !state.isEmpty, loading = state.isSubmitting),
            )
        }
    }
}

/** Слот идентифицируется своим временем — отдельного id у него нет. */
private fun LocalDateTime.slotId(): String = format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

private fun LocalDateTime.format(formatter: DateTimeFormatter): String = formatter.format(this)

/**
 * Слоты уже посчитаны в зоне приложения (`DateTimeFormatters.AppZone`), поэтому
 * здесь остаётся только вывести часы и минуты.
 */
private val SLOT_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
