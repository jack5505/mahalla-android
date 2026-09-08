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
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.feature.food.domain.CartLine
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums

/**
 * Корзина (эпик 5.2): количество, модификаторы строкой и итог моноширинными
 * цифрами.
 */
@Composable
fun CartScreen(
    onCheckout: (String) -> Unit,
    onAddMore: (placeId: String, placeName: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CartViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is CartEffect.OpenCheckout -> onCheckout(effect.placeId)
                is CartEffect.OpenMenu -> onAddMore(effect.placeId, effect.placeName)
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
            // Одна строка, а не «позиции + скидка + итог»: до оформления
            // корзина знает только сумму позиций — ни скидки, ни доставки
            // бэкенд не сообщает, и три одинаковых числа подряд читались бы
            // как ошибка расчёта.
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
