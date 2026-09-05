package uz.mahalla.feature.fashion.ui.catalog

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
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.ui.components.EmptyState
import uz.mahalla.core.ui.components.ListSkeleton
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.FilterChipUi
import uz.mahalla.core.ui.components.MahallaFilterRow
import uz.mahalla.core.ui.components.MahallaIconButton
import uz.mahalla.core.ui.components.MahallaPullToRefresh
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.fashion.domain.FashionCategory
import uz.mahalla.feature.fashion.domain.FashionProduct
import uz.mahalla.feature.fashion.ui.FashionFailure
import uz.mahalla.feature.fashion.ui.FashionLoadMore
import uz.mahalla.feature.fashion.ui.priceText
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums

/**
 * Витрина магазина одежды (issue #108): категории и товары.
 *
 * Фотографий на карточках нет — загрузчика изображений в проекте пока нет
 * (#60). Поэтому карточка построена вокруг текста: название, бренд, цена и
 * метки «новинка»/«хит», а не вокруг пустого места под картинку.
 */
@Composable
fun FashionCatalogScreen(
    onProductClick: (String) -> Unit,
    onCartClick: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FashionCatalogViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Корзину могли пополнить с карточки товара — бейдж обязан это показать.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(FashionCatalogEvent.ScreenResumed)
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is FashionCatalogEffect.OpenProduct -> onProductClick(effect.productId)
                FashionCatalogEffect.OpenCart -> onCartClick()
            }
        }
    }

    FashionCatalogContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun FashionCatalogContent(
    state: FashionCatalogState,
    onEvent: (FashionCatalogEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(
            title = state.placeName.takeIf(String::isNotBlank)
                ?: stringResource(R.string.fashion_catalog_title),
            onBack = onBack,
            actions = {
                CartAction(
                    count = state.cartCount,
                    onClick = { onEvent(FashionCatalogEvent.CartClicked) },
                )
            },
        )

        CategoriesRow(state = state, onEvent = onEvent)

        MahallaPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(FashionCatalogEvent.Refreshed) },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.gap),
            ) {
                productItems(state = state, onEvent = onEvent)
            }
        }
    }
}

/**
 * Полоса категорий. Пустой справочник и его отказ полосу просто убирают:
 * фильтровать нечем, а товары приехали и без него — прятать витрину из-за
 * справочника незачем.
 */
@Composable
private fun CategoriesRow(
    state: FashionCatalogState,
    onEvent: (FashionCatalogEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val categories = state.categories as? ScreenState.Content ?: return
    val chips = buildList {
        add(FilterChipUi(id = ALL_CATEGORIES, label = stringResource(R.string.fashion_category_all)))
        categories.data.forEach { add(FilterChipUi(id = it.id, label = it.name)) }
    }
    MahallaFilterRow(
        items = chips,
        selectedId = state.selectedCategoryId ?: ALL_CATEGORIES,
        // Чип «все» — не категория, а её отсутствие: пустой id в запрос не
        // уходит (бэкенд разобрал бы его как битый uuid).
        onSelect = { id ->
            onEvent(FashionCatalogEvent.CategorySelected(id.takeIf { it != ALL_CATEGORIES }))
        },
        modifier = modifier.padding(horizontal = Spacing.gutter),
    )
}

/** Идентификатор чипа «все категории»: сам бэкенд такого id не отдаст. */
private const val ALL_CATEGORIES = ""

/**
 * Состояния разложены руками, а не через `ScreenStateHost`: тот рисует
 * `ApiErrorState` с собственной прокруткой, а внутри `LazyColumn` вложенная
 * прокрутка меряется бесконечной высотой (issue #62).
 */
private fun LazyListScope.productItems(
    state: FashionCatalogState,
    onEvent: (FashionCatalogEvent) -> Unit,
) {
    when (val products = state.products) {
        is ScreenState.Loading -> item(key = "loading") { ListSkeleton(itemCount = LIST_SKELETONS) }

        is ScreenState.Empty -> item(key = "empty") {
            EmptyState(
                title = stringResource(R.string.fashion_catalog_empty_title),
                description = stringResource(R.string.fashion_catalog_empty_description),
                icon = Icons.Outlined.Checkroom,
            )
        }

        is ScreenState.Error -> item(key = "error") {
            FashionFailure(
                failure = products.failure,
                onRetry = { onEvent(FashionCatalogEvent.Retry) },
            )
        }

        is ScreenState.Content -> {
            items(products.data, key = FashionProduct::id) { product ->
                ProductCard(
                    product = product,
                    onClick = { onEvent(FashionCatalogEvent.ProductClicked(product.id)) },
                )
            }
            if (state.hasMore || state.loadMoreFailure != null) {
                item(key = "load-more") {
                    FashionLoadMore(
                        itemCount = products.data.size,
                        failure = state.loadMoreFailure,
                        onLoadMore = { onEvent(FashionCatalogEvent.LoadMore) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProductCard(
    product: FashionProduct,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMahallaColors.current
    MahallaCard(modifier = modifier, onClick = onClick) {
        Text(
            text = product.name.takeIf(String::isNotBlank)
                ?: stringResource(R.string.fashion_product_unnamed),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        product.brand?.let { brand ->
            Text(
                text = brand,
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
                text = priceText(product.priceSum),
                style = MaterialTheme.typography.titleMedium.merge(TabularNums),
                color = MaterialTheme.colorScheme.onSurface,
            )
            // Старая цена рядом, а не вместо: скидка видна только в сравнении.
            if (product.hasDiscount) {
                Text(
                    text = priceText(product.basePriceSum),
                    style = MaterialTheme.typography.bodySmall.merge(TabularNums),
                    color = colors.fgMuted,
                )
            }
        }

        val labels = buildList {
            if (product.isNew) add(stringResource(R.string.fashion_badge_new) to MahallaTone.Info)
            if (product.isBestseller) {
                add(stringResource(R.string.fashion_badge_bestseller) to MahallaTone.Success)
            }
        }
        if (labels.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = Spacing.item),
                horizontalArrangement = Arrangement.spacedBy(Spacing.item / 2),
            ) {
                labels.forEach { (text, tone) -> MahallaBadge(text = text, tone = tone) }
            }
        }
    }
}

/**
 * Кнопка корзины с количеством. Число в подписи, а не точкой: «сколько там
 * лежит» — это и есть вопрос, ради которого на неё смотрят.
 */
@Composable
private fun CartAction(count: Int, onClick: () -> Unit) {
    val label = if (count > 0) {
        pluralStringResource(R.plurals.fashion_cart_action_count, count, count)
    } else {
        stringResource(R.string.fashion_cart_title)
    }
    MahallaIconButton(
        icon = Icons.Outlined.ShoppingCart,
        contentDescription = label,
        onClick = onClick,
    )
}

private const val LIST_SKELETONS = 4

@ThemeLanguagePreviews
@Composable
private fun FashionCatalogPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        FashionCatalogContent(
            state = FashionCatalogState(
                placeName = "Zara Tashkent",
                categories = ScreenState.Content(
                    listOf(
                        FashionCategory(id = "c-1", name = "Ko'ylaklar"),
                        FashionCategory(id = "c-2", name = "Shimlar"),
                    ),
                ),
                selectedCategoryId = "c-1",
                products = ScreenState.Content(
                    listOf(
                        FashionProduct(
                            id = "p-1",
                            storeId = "s-1",
                            name = "Oq ko'ylak",
                            brand = "Mahalla",
                            basePriceSum = 320_000,
                            salePriceSum = 240_000,
                            isNew = true,
                        ),
                        FashionProduct(
                            id = "p-2",
                            storeId = "s-1",
                            name = "Jinsi shim",
                            basePriceSum = 410_000,
                            isBestseller = true,
                        ),
                    ),
                ),
                cartCount = 2,
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
