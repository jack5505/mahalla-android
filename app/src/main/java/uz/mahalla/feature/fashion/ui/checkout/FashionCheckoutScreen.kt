package uz.mahalla.feature.fashion.ui.checkout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.CardSkeleton
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaChoiceCard
import uz.mahalla.core.ui.components.MahallaSegmentedControl
import uz.mahalla.core.ui.components.MahallaTextField
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.SectionHeader
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.feature.fashion.domain.FashionCartItem
import uz.mahalla.feature.fashion.ui.FashionFailure
import uz.mahalla.feature.fashion.ui.priceText
import uz.mahalla.feature.food.domain.CartTotals
import uz.mahalla.feature.food.domain.CheckoutError
import uz.mahalla.feature.food.domain.DeliveryMethod
import uz.mahalla.feature.food.domain.PaymentMethod
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums

/**
 * Оформление заказа одежды (issue #108): способ получения, адрес, оплата.
 *
 * Форма — та же, что у «Еды»: у бэкенда это один и тот же
 * `PlaceOrderRequest`. Ни комментария, ни времени доставки в нём нет, поэтому
 * их нет и на экране — поле, которое некуда отправить, обещало бы человеку
 * то, о чём магазин не узнает.
 */
@Composable
fun FashionCheckoutScreen(
    onOpenOrders: () -> Unit,
    onOpenWallet: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FashionCheckoutViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                FashionCheckoutEffect.OpenOrders -> onOpenOrders()
                FashionCheckoutEffect.OpenWallet -> onOpenWallet()
            }
        }
    }

    FashionCheckoutContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun FashionCheckoutContent(
    state: FashionCheckoutState,
    onEvent: (FashionCheckoutEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(title = stringResource(R.string.fashion_checkout_title), onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(Spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.gap),
        ) {
            when {
                !state.isLoaded -> CardSkeleton()

                state.loadFailure != null -> FashionFailure(
                    failure = state.loadFailure,
                    onRetry = { onEvent(FashionCheckoutEvent.Retry) },
                )

                // Корзину могли забрать в заказ на другом устройстве, пока
                // человек шёл сюда: оформлять нечего, и форма только мешала бы.
                state.isEmpty -> Text(
                    text = stringResource(R.string.fashion_checkout_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )

                else -> CheckoutForm(state = state, onEvent = onEvent)
            }
        }
    }
}

@Composable
private fun CheckoutForm(
    state: FashionCheckoutState,
    onEvent: (FashionCheckoutEvent) -> Unit,
) {
    val colors = LocalMahallaColors.current

    SectionHeader(title = stringResource(R.string.fashion_checkout_items))
    MahallaCard {
        state.items.forEach { item -> OrderLine(item = item) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.item),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.fashion_cart_total),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fgMuted,
            )
            Text(
                text = priceText(state.totals.totalSum),
                style = MaterialTheme.typography.titleMedium.merge(TabularNums),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    // Стоимость доставки бэкенд называет только в ответе о созданном заказе —
    // до оформления её не знает никто, и выдумывать её здесь нельзя.
    Text(
        text = stringResource(R.string.fashion_checkout_delivery_note),
        style = MaterialTheme.typography.bodySmall,
        color = colors.fgMuted,
    )

    SectionHeader(title = stringResource(R.string.checkout_method))
    MahallaSegmentedControl(
        options = listOf(
            stringResource(R.string.checkout_method_delivery),
            stringResource(R.string.checkout_method_pickup),
        ),
        selectedIndex = if (state.form.method == DeliveryMethod.Delivery) 0 else 1,
        onSelect = { index ->
            onEvent(
                FashionCheckoutEvent.MethodSelected(
                    if (index == 0) DeliveryMethod.Delivery else DeliveryMethod.Pickup,
                ),
            )
        },
        enabled = !state.orderCreated,
    )

    if (state.form.needsAddress) {
        MahallaTextField(
            value = state.form.address,
            onValueChange = { onEvent(FashionCheckoutEvent.AddressChanged(it)) },
            label = stringResource(R.string.checkout_address),
            enabled = !state.orderCreated,
            errorText = state.error { it is CheckoutError.AddressRequired }
                ?.let { stringResource(R.string.checkout_error_address) },
        )
    }

    SectionHeader(title = stringResource(R.string.checkout_payment))
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        MahallaChoiceCard(
            title = stringResource(R.string.checkout_payment_wallet),
            selected = state.form.payment == PaymentMethod.Wallet,
            onClick = { onEvent(FashionCheckoutEvent.PaymentSelected(PaymentMethod.Wallet)) },
            note = state.walletNote(),
            enabled = !state.orderCreated,
        )
        MahallaChoiceCard(
            title = stringResource(R.string.checkout_payment_cash),
            selected = state.form.payment == PaymentMethod.Cash,
            onClick = { onEvent(FashionCheckoutEvent.PaymentSelected(PaymentMethod.Cash)) },
            enabled = !state.orderCreated,
        )
    }

    // Сколько не хватает — числом: «недостаточно средств» не отвечает на
    // вопрос, сколько пополнять.
    state.insufficientFunds?.let { error ->
        Text(
            text = stringResource(
                R.string.checkout_error_insufficient_funds,
                priceText(error.missingSum),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        MahallaButton(
            text = stringResource(R.string.checkout_top_up),
            onClick = { onEvent(FashionCheckoutEvent.TopUpClicked) },
            variant = MahallaButtonVariant.Secondary,
        )
    }

    state.submitError?.let { FashionFailure(failure = it) }

    if (state.orderCreated) {
        // Экран не уходит сам: молчаливый переход читается как «ничего не
        // произошло» (issue #49).
        MahallaCard {
            Text(
                text = stringResource(R.string.fashion_order_created),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            MahallaButton(
                text = stringResource(R.string.fashion_open_orders),
                onClick = { onEvent(FashionCheckoutEvent.OrdersClicked) },
                modifier = Modifier.padding(top = Spacing.item),
            )
        }
    } else {
        MahallaButton(
            text = stringResource(R.string.fashion_checkout_submit),
            onClick = { onEvent(FashionCheckoutEvent.SubmitClicked) },
            state = ButtonState(enabled = !state.isSubmitting, loading = state.isSubmitting),
        )
    }
}

@Composable
private fun OrderLine(item: FashionCartItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.item / 2),
        horizontalArrangement = Arrangement.spacedBy(Spacing.item),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.productName.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.fashion_product_unnamed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val details = listOfNotNull(
                item.variantLabel.takeIf(String::isNotBlank),
                stringResource(R.string.quantity_value, item.quantity),
            )
            Text(
                text = details.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = LocalMahallaColors.current.fgMuted,
            )
        }
        Text(
            text = priceText(item.totalSum),
            style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Подпись кошелька: сколько на нём есть. Баланс не приехал — подписи нет:
 * «0 сум» на неотвеченном запросе — прямая ложь о деньгах.
 */
@Composable
private fun FashionCheckoutState.walletNote(): String? =
    if (balanceKnown) {
        stringResource(R.string.checkout_wallet_balance, priceText(walletBalanceSum))
    } else {
        null
    }

@ThemeLanguagePreviews
@Composable
private fun FashionCheckoutPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        FashionCheckoutContent(
            state = FashionCheckoutState(
                storeId = "s-1",
                isLoaded = true,
                items = listOf(
                    FashionCartItem(
                        variantId = "v-1",
                        storeId = "s-1",
                        productName = "Oq ko'ylak",
                        colorName = "Oq",
                        size = "M",
                        unitPriceSum = 240_000,
                        quantity = 2,
                    ),
                ),
                totals = CartTotals(subtotalSum = 480_000),
                balanceKnown = true,
                walletBalanceSum = 1_000_000,
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
