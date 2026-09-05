package uz.mahalla.feature.fashion.ui.product

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.CardSkeleton
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaFilterChip
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.SectionHeader
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.fashion.domain.FashionProductDetail
import uz.mahalla.feature.fashion.domain.ProductVariant
import uz.mahalla.feature.fashion.ui.FashionFailure
import uz.mahalla.feature.fashion.ui.priceText
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums

/**
 * Карточка товара одежды (issue #108): цвет → размер → «в корзину».
 *
 * Фотографий нет — загрузчика изображений в проекте пока нет (#60), а
 * `VariantResponse.images` приходит строкой неизвестного формата. Поэтому
 * цвета показываются названиями, а не образцами ткани.
 */
@Composable
fun FashionProductScreen(
    onCartClick: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FashionProductViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                FashionProductEffect.OpenCart -> onCartClick()
            }
        }
    }

    FashionProductContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun FashionProductContent(
    state: FashionProductState,
    onEvent: (FashionProductEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(
            title = state.detail?.name?.takeIf(String::isNotBlank)
                ?: stringResource(R.string.fashion_product_title),
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.gap),
        ) {
            when (val product = state.product) {
                is ScreenState.Loading -> CardSkeleton()

                is ScreenState.Empty -> Text(
                    text = stringResource(R.string.fashion_product_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                )

                is ScreenState.Error -> FashionFailure(
                    failure = product.failure,
                    onRetry = { onEvent(FashionProductEvent.Retry) },
                )

                is ScreenState.Content -> ProductBody(
                    detail = product.data,
                    state = state,
                    onEvent = onEvent,
                )
            }
        }
    }
}

@Composable
private fun ProductBody(
    detail: FashionProductDetail,
    state: FashionProductState,
    onEvent: (FashionProductEvent) -> Unit,
) {
    val colors = LocalMahallaColors.current

    Text(
        text = detail.name.takeIf(String::isNotBlank)
            ?: stringResource(R.string.fashion_product_unnamed),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )

    detail.brand?.let { brand ->
        Text(
            text = brand,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.fgMuted,
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.item),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Цена выбранного варианта: XXL может стоить дороже S, и общая цена
        // товара там, где платят другую, — обман.
        Text(
            text = priceText(detail.priceOf(state.selectedVariant)),
            style = MaterialTheme.typography.headlineSmall.merge(TabularNums),
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (detail.isNew) {
            MahallaBadge(text = stringResource(R.string.fashion_badge_new), tone = MahallaTone.Info)
        }
        if (detail.isBestseller) {
            MahallaBadge(
                text = stringResource(R.string.fashion_badge_bestseller),
                tone = MahallaTone.Success,
            )
        }
    }

    detail.description?.let { description ->
        Text(text = description, style = MaterialTheme.typography.bodyMedium)
    }

    if (detail.variants.isEmpty()) {
        // Пустая карточка объясняет меньше, чем строка текста: товар в
        // каталоге есть, а купить его нечем — это ответ сервера, а не поломка.
        Text(
            text = stringResource(R.string.fashion_product_no_variants),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.fgMuted,
        )
    } else {
        ColorsBlock(detail = detail, state = state, onEvent = onEvent)
        SizesBlock(detail = detail, state = state, onEvent = onEvent)
    }

    DetailsBlock(detail = detail)

    state.addFailure?.let { FashionFailure(failure = it) }

    // Подтверждение вместо молчаливого перехода: «добавлено» без слов
    // читается как «ничего не произошло» (issue #49).
    if (state.added) {
        MahallaCard {
            Text(
                text = stringResource(R.string.fashion_product_added),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            MahallaButton(
                text = stringResource(R.string.fashion_open_cart),
                onClick = { onEvent(FashionProductEvent.CartClicked) },
                modifier = Modifier.padding(top = Spacing.item),
                variant = MahallaButtonVariant.Secondary,
            )
        }
    }

    MahallaButton(
        text = stringResource(R.string.fashion_add_to_cart),
        onClick = { onEvent(FashionProductEvent.AddToCartClicked) },
        state = ButtonState(enabled = state.canAddToCart, loading = state.isAdding),
    )

    // Почему кнопка не нажимается — словами: выключенная кнопка без
    // объяснения читается как сломанная.
    if (!state.canAddToCart && !state.isAdding && detail.variants.isNotEmpty()) {
        Text(
            text = stringResource(R.string.fashion_variant_out_of_stock),
            style = MaterialTheme.typography.bodySmall,
            color = colors.fgMuted,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorsBlock(
    detail: FashionProductDetail,
    state: FashionProductState,
    onEvent: (FashionProductEvent) -> Unit,
) {
    val colors = detail.colors
    // Один цвет выбирать не из чего — полоса с единственным чипом только
    // отнимает место.
    if (colors.size < 2) return

    SectionHeader(title = stringResource(R.string.fashion_colors))
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        colors.forEach { color ->
            MahallaFilterChip(
                label = color,
                selected = color == state.selectedColor,
                onClick = { onEvent(FashionProductEvent.ColorSelected(color)) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SizesBlock(
    detail: FashionProductDetail,
    state: FashionProductState,
    onEvent: (FashionProductEvent) -> Unit,
) {
    val color = state.selectedColor ?: return
    val variants = detail.variantsOf(color)
    if (variants.isEmpty()) return

    SectionHeader(title = stringResource(R.string.fashion_sizes))
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        variants.forEach { variant ->
            MahallaFilterChip(
                label = variant.size.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.fashion_size_unnamed),
                selected = variant.id == state.selectedVariantId,
                onClick = { onEvent(FashionProductEvent.VariantSelected(variant.id)) },
                // Размера нет в наличии — чип виден, но не выбирается:
                // спрятать его значит скрыть, что размер вообще существует.
                enabled = variant.isOrderable,
            )
        }
    }
}

/** Состав, уход и размерная сетка — то, из-за чего вещь возвращают. */
@Composable
private fun DetailsBlock(detail: FashionProductDetail) {
    val rows = listOfNotNull(
        detail.material?.let { stringResource(R.string.fashion_material) to it },
        detail.careInstructions?.let { stringResource(R.string.fashion_care) to it },
        detail.sizeGuide?.let { stringResource(R.string.fashion_size_guide) to it },
    )
    if (rows.isEmpty()) return

    MahallaCard {
        rows.forEachIndexed { index, (label, value) ->
            Text(
                text = label,
                modifier = Modifier.padding(top = if (index == 0) 0.dp else Spacing.item),
                style = MaterialTheme.typography.labelLarge,
                color = LocalMahallaColors.current.fgMuted,
            )
            Text(text = value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@ThemeLanguagePreviews
@Composable
private fun FashionProductPreview() {
    val detail = FashionProductDetail(
        id = "p-1",
        storeId = "s-1",
        name = "Oq ko'ylak",
        brand = "Mahalla",
        description = "Paxta 100%, kundalik kiyim uchun.",
        material = "Paxta 100%",
        basePriceSum = 320_000,
        salePriceSum = 240_000,
        isNew = true,
        variants = listOf(
            ProductVariant(id = "v-1", colorName = "Oq", size = "M", priceSum = 240_000),
            ProductVariant(
                id = "v-2",
                colorName = "Oq",
                size = "L",
                priceSum = 240_000,
                stockQuantity = 0,
            ),
            ProductVariant(id = "v-3", colorName = "Qora", size = "M", priceSum = 260_000),
        ),
    )
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        FashionProductContent(
            state = FashionProductState(
                product = ScreenState.Content(detail),
                selectedVariantId = "v-1",
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
