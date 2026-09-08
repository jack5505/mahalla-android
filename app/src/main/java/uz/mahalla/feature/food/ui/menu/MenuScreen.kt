package uz.mahalla.feature.food.ui.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.format.MoneyFormatter
import uz.mahalla.core.ui.components.EmptyState
import uz.mahalla.core.ui.components.FilterChipUi
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaComponentDefaults
import uz.mahalla.core.ui.components.MahallaDialog
import uz.mahalla.core.ui.components.MahallaFilterRow
import uz.mahalla.core.ui.components.MahallaThumbnail
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.ScreenStateHost
import uz.mahalla.feature.food.domain.MenuCategory
import uz.mahalla.feature.food.domain.MenuItem
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums

/**
 * Меню заведения (эпик 5.1): категории, позиции, стоп-лист, шторка
 * модификаторов и нижняя панель с корзиной.
 */
@Composable
fun MenuScreen(
    onCartClick: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MenuViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is MenuEffect.OpenCart -> onCartClick(effect.placeId)
                MenuEffect.NavigateBack -> onBack()
                // Добавление и так видно по счётчику в нижней панели —
                // снекбар поверх него был бы шумом.
                is MenuEffect.ItemAdded -> Unit
            }
        }
    }

    MenuContent(state = state, onEvent = viewModel::onEvent, onBack = onBack, modifier = modifier)
}

@Composable
fun MenuContent(
    state: MenuState,
    onEvent: (MenuEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(
            title = state.placeName.ifBlank { stringResource(R.string.menu_title) },
            onBack = onBack,
        )

        ScreenStateHost(
            state = state.menu,
            onRetry = { onEvent(MenuEvent.Retry) },
            modifier = Modifier.weight(1f),
            empty = {
                EmptyState(
                    title = stringResource(R.string.menu_empty_title),
                    description = stringResource(R.string.menu_empty_description),
                )
            },
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (state.categories.size > 1) {
                    MahallaFilterRow(
                        items = state.categories.map { FilterChipUi(id = it.id, label = it.name) },
                        selectedId = state.visibleCategory?.id,
                        onSelect = { onEvent(MenuEvent.CategorySelected(it)) },
                        modifier = Modifier.padding(horizontal = Spacing.gutter),
                    )
                }
                CategoryItems(
                    category = state.visibleCategory,
                    onEvent = onEvent,
                    modifier = Modifier.padding(horizontal = Spacing.gutter),
                )
            }
        }

        if (state.hasCart) {
            CartBar(state = state, onEvent = onEvent)
        }
    }

    val sheet = state.sheet
    if (sheet != null) {
        OptionsSheet(
            sheet = sheet,
            onEvent = onEvent,
            onDismiss = { onEvent(MenuEvent.SheetDismissed) },
        )
    }

    val conflictPlace = state.conflictPlaceName
    if (conflictPlace != null) {
        MahallaDialog(
            title = stringResource(R.string.cart_conflict_title),
            text = stringResource(R.string.cart_conflict_description, conflictPlace),
            confirmLabel = stringResource(R.string.cart_conflict_confirm),
            onConfirm = { onEvent(MenuEvent.ConflictConfirmed) },
            onDismiss = { onEvent(MenuEvent.ConflictDismissed) },
            destructive = true,
        )
    }
}

@Composable
private fun CategoryItems(
    category: MenuCategory?,
    onEvent: (MenuEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.gap),
        contentPadding = PaddingValues(vertical = Spacing.gap),
    ) {
        items(items = category?.items.orEmpty(), key = MenuItem::id) { item ->
            MenuItemRow(item = item, onClick = { onEvent(MenuEvent.ItemClicked(item.id)) })
        }
    }
}

/**
 * Позиция меню. Из стоп-листа она не исчезает, а перестаёт нажиматься и
 * получает бейдж: пропавшее блюдо человек будет искать глазами и решит, что
 * ошибся заведением.
 */
@Composable
private fun MenuItemRow(
    item: MenuItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mahalla = LocalMahallaColors.current
    MahallaCard(
        modifier = modifier.heightIn(min = MahallaComponentDefaults.menuItemMinHeight),
        onClick = onClick.takeIf { item.isOrderable },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.gap),
            verticalAlignment = Alignment.Top,
        ) {
            // Фото блюда декоративное: название читается строкой рядом. Место
            // под миниатюру занимается только когда ссылка есть — контракт
            // бэкенда её пока не отдаёт (issue #9), и ряд одинаковых
            // фоллбэк-иконок был бы шумом, а не вёрсткой.
            if (item.photoUrl != null) {
                MahallaThumbnail(
                    url = item.photoUrl,
                    contentDescription = null,
                    fallbackIcon = Icons.Outlined.RestaurantMenu,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (item.isOrderable) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        mahalla.fgMuted
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.description != null) {
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = mahalla.fgMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = MoneyFormatter.withCurrency(
                        item.priceSum,
                        stringResource(R.string.currency_uzs),
                    ),
                    style = MaterialTheme.typography.titleMedium.merge(TabularNums),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (!item.isOrderable) {
                MahallaBadge(
                    text = stringResource(R.string.menu_item_unavailable),
                    tone = MahallaTone.Neutral,
                )
            } else if (item.hasOptions) {
                MahallaBadge(
                    text = stringResource(R.string.menu_item_has_options),
                    tone = MahallaTone.Accent,
                )
            }
        }
    }
}

/** Нижняя панель: сколько позиций в корзине и на какую сумму. */
@Composable
private fun CartBar(
    state: MenuState,
    onEvent: (MenuEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.gutter, vertical = Spacing.gap),
            horizontalArrangement = Arrangement.spacedBy(Spacing.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = MoneyFormatter.withCurrency(
                    state.cartTotalSum,
                    stringResource(R.string.currency_uzs),
                ),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium.merge(TabularNums),
            )
            MahallaButton(
                text = stringResource(R.string.cart_open_with_count, state.cartItemCount),
                onClick = { onEvent(MenuEvent.CartClicked) },
                icon = Icons.Outlined.ShoppingCart,
                fillWidth = false,
            )
        }
    }
}
