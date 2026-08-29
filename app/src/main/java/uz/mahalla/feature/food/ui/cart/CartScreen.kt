package uz.mahalla.feature.food.ui.cart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.format.MoneyFormatter
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.EmptyState
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaQuantityStepper
import uz.mahalla.core.ui.components.MahallaTextField
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.feature.food.domain.CartLine
import uz.mahalla.feature.food.domain.PromoFailure
import uz.mahalla.feature.food.domain.PromoState
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums

/**
 * Корзина (эпик 5.2): количество, модификаторы строкой, промокод и итог
 * моноширинными цифрами.
 */
@Composable
fun CartScreen(
    onCheckout: (String) -> Unit,
    onAddMore: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CartViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is CartEffect.OpenCheckout -> onCheckout(effect.placeId)
                is CartEffect.OpenMenu -> onAddMore(effect.placeId)
                CartEffect.NavigateBack -> onBack()
            }
        }
    }

    CartContent(state = state, onEvent = viewModel::onEvent, onBack = onBack, modifier = modifier)
}

@Composable
fun CartContent(
    state: CartState,
    onEvent: (CartEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(
            title = stringResource(R.string.cart_title),
            onBack = onBack,
        )

        if (state.isLoaded && state.isEmpty) {
            EmptyState(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.cart_empty_title),
                description = stringResource(R.string.cart_empty_description),
                actionLabel = stringResource(R.string.cart_add_more),
                onAction = { onEvent(CartEvent.AddMoreClicked) },
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.gap),
            contentPadding = PaddingValues(vertical = Spacing.gap),
        ) {
            if (state.placeName.isNotBlank()) {
                item(key = "place") {
                    Text(
                        text = state.placeName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
            items(items = state.lines, key = CartLine::id) { line ->
                CartLineCard(line = line, onEvent = onEvent)
            }
            item(key = "add-more") {
                MahallaButton(
                    text = stringResource(R.string.cart_add_more),
                    onClick = { onEvent(CartEvent.AddMoreClicked) },
                    variant = MahallaButtonVariant.Ghost,
                )
            }
            item(key = "promo") {
                PromoBlock(state = state, onEvent = onEvent)
            }
        }

        CheckoutBar(state = state, onEvent = onEvent)
    }
}

@Composable
private fun CartLineCard(
    line: CartLine,
    onEvent: (CartEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currency = stringResource(R.string.currency_uzs)
    MahallaCard(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = line.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (line.optionsLabel.isNotBlank()) {
                    Text(
                        text = line.optionsLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalMahallaColors.current.fgMuted,
                    )
                }
                Text(
                    text = MoneyFormatter.withCurrency(line.totalSum, currency),
                    style = MaterialTheme.typography.titleMedium.merge(TabularNums),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            MahallaQuantityStepper(
                quantity = line.quantity,
                onQuantityChange = { onEvent(CartEvent.QuantityChanged(line.id, it)) },
                itemName = line.name,
            )
        }
    }
}

/** Промокод: поле, кнопка и результат проверки текстом, а не только цветом. */
@Composable
private fun PromoBlock(
    state: CartState,
    onEvent: (CartEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val applied = state.promo as? PromoState.Applied
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
        if (applied != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.cart_promo_applied, applied.promo.code),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalMahallaColors.current.success,
                )
                MahallaButton(
                    text = stringResource(R.string.action_delete),
                    onClick = { onEvent(CartEvent.PromoRemoved) },
                    variant = MahallaButtonVariant.Ghost,
                    fillWidth = false,
                )
            }
            return@Column
        }

        MahallaTextField(
            value = state.promoInput,
            onValueChange = { onEvent(CartEvent.PromoInputChanged(it)) },
            label = stringResource(R.string.cart_promo_label),
            placeholder = stringResource(R.string.cart_promo_placeholder),
            errorText = (state.promo as? PromoState.Rejected)?.reason?.text(),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Done,
            ),
        )
        MahallaButton(
            text = stringResource(R.string.cart_promo_apply),
            onClick = { onEvent(CartEvent.PromoApplied) },
            variant = MahallaButtonVariant.Secondary,
            state = ButtonState(
                enabled = state.canApplyPromo,
                loading = state.promo is PromoState.Checking,
            ),
        )
    }
}

/** Нижняя панель: итог и переход к оформлению. */
@Composable
private fun CheckoutBar(
    state: CartState,
    onEvent: (CartEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currency = stringResource(R.string.currency_uzs)
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
            TotalRow(
                label = stringResource(R.string.cart_subtotal),
                value = MoneyFormatter.withCurrency(state.totals.subtotalSum, currency),
            )
            if (state.totals.hasDiscount) {
                TotalRow(
                    label = stringResource(R.string.cart_discount),
                    value = "−" + MoneyFormatter.withCurrency(state.totals.discountSum, currency),
                    highlighted = true,
                )
            }
            TotalRow(
                label = stringResource(R.string.cart_total),
                value = MoneyFormatter.withCurrency(state.totals.totalSum, currency),
                emphasized = true,
            )
            MahallaButton(
                text = stringResource(R.string.cart_checkout),
                onClick = { onEvent(CartEvent.CheckoutClicked) },
                state = ButtonState(enabled = state.canCheckout),
            )
        }
    }
}

@Composable
private fun TotalRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    highlighted: Boolean = false,
) {
    val mahalla = LocalMahallaColors.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = if (emphasized) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = if (emphasized) MaterialTheme.colorScheme.onSurface else mahalla.fgMuted,
        )
        Text(
            text = value,
            style = (
                if (emphasized) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.bodyMedium
                }
                ).merge(TabularNums),
            color = when {
                highlighted -> mahalla.success
                emphasized -> MaterialTheme.colorScheme.onSurface
                else -> mahalla.fgMuted
            },
        )
    }
}

/** Тексты отказа промокода. Домен знает причину, ресурсы — формулировку. */
@Composable
private fun PromoFailure.text(): String = when (this) {
    PromoFailure.NotFound -> stringResource(R.string.cart_promo_error_not_found)
    PromoFailure.Expired -> stringResource(R.string.cart_promo_error_expired)
    is PromoFailure.MinOrder -> stringResource(
        R.string.cart_promo_error_min_order,
        MoneyFormatter.withCurrency(minOrderSum, stringResource(R.string.currency_uzs)),
    )

    PromoFailure.Network -> stringResource(R.string.cart_promo_error_network)
}
