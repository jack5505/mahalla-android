package uz.mahalla.feature.fashion.ui.cart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.EmptyState
import uz.mahalla.core.ui.components.ListSkeleton
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaDialog
import uz.mahalla.core.ui.components.MahallaPullToRefresh
import uz.mahalla.core.ui.components.MahallaQuantityStepper
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.SectionHeader
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.fashion.domain.FashionCart
import uz.mahalla.feature.fashion.domain.FashionCartItem
import uz.mahalla.feature.fashion.domain.FashionCartRules
import uz.mahalla.feature.fashion.domain.FashionCartStore
import uz.mahalla.feature.fashion.ui.FashionFailure
import uz.mahalla.feature.fashion.ui.priceText
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums

/**
 * Корзина одежды (issue #108) — серверная и общая на все магазины, поэтому
 * показывается разделами: каждый магазин оформляется отдельным заказом
 * (`PlaceOrderRequest` принимает ровно один `placeId`).
 */
@Composable
fun FashionCartScreen(
    onCheckout: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FashionCartViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Корзину могли пополнить с карточки товара, а строку — забрать в заказ
    // на другом устройстве.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(FashionCartEvent.ScreenResumed)
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is FashionCartEffect.OpenCheckout -> onCheckout(effect.storeId)
            }
        }
    }

    FashionCartContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun FashionCartContent(
    state: FashionCartState,
    onEvent: (FashionCartEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(title = stringResource(R.string.fashion_cart_title), onBack = onBack)

        MahallaPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(FashionCartEvent.Refreshed) },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.gap),
            ) {
                // Отказ изменения — над корзиной, а не вместо неё: строки уже
                // на экране, и прятать их из-за неудавшейся кнопки незачем.
                state.actionFailure?.let { failure ->
                    item(key = "action-failure") { FashionFailure(failure = failure) }
                }
                cartItems(state = state, onEvent = onEvent)
            }
        }
    }

    state.confirmRemove?.let {
        MahallaDialog(
            title = stringResource(R.string.fashion_cart_remove_title),
            text = stringResource(R.string.fashion_cart_remove_message),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { onEvent(FashionCartEvent.RemoveConfirmed) },
            onDismiss = { onEvent(FashionCartEvent.RemoveDismissed) },
            dismissLabel = stringResource(R.string.fashion_cart_remove_keep),
            destructive = true,
        )
    }
}

/**
 * Состояния разложены руками, а не через `ScreenStateHost`: тот рисует
 * `ApiErrorState` с собственной прокруткой, а внутри `LazyColumn` вложенная
 * прокрутка меряется бесконечной высотой (issue #62).
 */
private fun LazyListScope.cartItems(
    state: FashionCartState,
    onEvent: (FashionCartEvent) -> Unit,
) {
    when (val cart = state.cart) {
        is ScreenState.Loading -> item(key = "loading") { ListSkeleton(itemCount = LIST_SKELETONS) }

        is ScreenState.Empty -> item(key = "empty") {
            EmptyState(
                title = stringResource(R.string.fashion_cart_empty_title),
                description = stringResource(R.string.fashion_cart_empty_description),
                icon = Icons.Outlined.ShoppingCart,
            )
        }

        is ScreenState.Error -> item(key = "error") {
            FashionFailure(
                failure = cart.failure,
                onRetry = { onEvent(FashionCartEvent.Retry) },
            )
        }

        is ScreenState.Content -> cart.data.stores.forEach { store ->
            storeSection(store = store, state = state, onEvent = onEvent)
        }
    }
}

/**
 * Раздел одного магазина: его строки и его же кнопка оформления. Имя магазина
 * в ответе корзины бэкенд не отдаёт вовсе — раздел подписан порядковым
 * заголовком, а не выдуманным названием.
 */
private fun LazyListScope.storeSection(
    store: FashionCartStore,
    state: FashionCartState,
    onEvent: (FashionCartEvent) -> Unit,
) {
    item(key = "store-${store.storeId}") {
        SectionHeader(title = stringResource(R.string.fashion_cart_store))
    }
    items(store.items, key = FashionCartItem::variantId) { item ->
        CartLineCard(
            item = item,
            pending = state.pendingVariantId == item.variantId,
            // Пока идёт запрос по одной строке, остальные не трогаем: ответы
            // приехали бы на корзину, которой уже нет.
            enabled = state.pendingVariantId == null,
            onEvent = onEvent,
        )
    }
    item(key = "store-total-${store.storeId}") {
        StoreFooter(store = store, enabled = state.pendingVariantId == null, onEvent = onEvent)
    }
}

@Composable
private fun CartLineCard(
    item: FashionCartItem,
    pending: Boolean,
    enabled: Boolean,
    onEvent: (FashionCartEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMahallaColors.current
    MahallaCard(modifier = modifier) {
        Text(
            text = item.productName.takeIf(String::isNotBlank)
                ?: stringResource(R.string.fashion_product_unnamed),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        item.variantLabel.takeIf(String::isNotBlank)?.let { label ->
            Text(
                text = label,
                modifier = Modifier.padding(top = Spacing.item / 2),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fgMuted,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.item),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = priceText(item.totalSum),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium.merge(TabularNums),
                color = MaterialTheme.colorScheme.onSurface,
            )
            MahallaQuantityStepper(
                quantity = item.quantity,
                onQuantityChange = { quantity ->
                    if (enabled && !pending) {
                        onEvent(FashionCartEvent.QuantityChanged(item.variantId, quantity))
                    }
                },
                maxQuantity = FashionCartRules.MAX_QUANTITY,
                itemName = item.productName.takeIf(String::isNotBlank),
            )
        }
    }
}

/** Итог магазина и оформление: заказ собирается по одному магазину за раз. */
@Composable
private fun StoreFooter(
    store: FashionCartStore,
    enabled: Boolean,
    onEvent: (FashionCartEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.fashion_cart_total),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMahallaColors.current.fgMuted,
            )
            Text(
                text = priceText(store.totalSum),
                style = MaterialTheme.typography.titleMedium.merge(TabularNums),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        MahallaButton(
            text = stringResource(R.string.fashion_checkout),
            onClick = { onEvent(FashionCartEvent.CheckoutClicked(store.storeId)) },
            state = ButtonState(enabled = enabled),
        )
    }
}

private const val LIST_SKELETONS = 3

@ThemeLanguagePreviews
@Composable
private fun FashionCartPreview() {
    val cart = FashionCart(
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
            FashionCartItem(
                variantId = "v-2",
                storeId = "s-1",
                productName = "Jinsi shim",
                size = "32",
                unitPriceSum = 410_000,
                quantity = 1,
            ),
        ),
    )
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        FashionCartContent(
            state = FashionCartState(cart = ScreenState.Content(cart)),
            onEvent = {},
            onBack = {},
        )
    }
}
