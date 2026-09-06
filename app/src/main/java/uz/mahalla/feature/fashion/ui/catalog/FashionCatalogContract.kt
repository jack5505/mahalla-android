package uz.mahalla.feature.fashion.ui.catalog

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.fashion.domain.FashionCategory
import uz.mahalla.feature.fashion.domain.FashionProduct

/**
 * Витрина магазина одежды (issue #108).
 *
 * @param categories справочник категорий — **отдельное** состояние от
 * [products]: он общий на весь бэкенд и его отказ не повод прятать товары,
 * которые уже приехали.
 * @param selectedCategoryId выбранная категория фильтра; `null` — «все».
 * @param cartCount сколько единиц в серверной корзине — бейдж на кнопке
 * корзины. Ноль и отказ выглядят одинаково намеренно: соврать «у вас пусто»
 * из-за пропавшей сети хуже, чем не показать число.
 */
data class FashionCatalogState(
    val placeName: String = "",
    val categories: ScreenState<List<FashionCategory>> = ScreenState.Loading,
    val selectedCategoryId: String? = null,
    val products: ScreenState<List<FashionProduct>> = ScreenState.Loading,
    val isRefreshing: Boolean = false,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadMoreFailure: ApiFailure? = null,
    val cartCount: Int = 0,
) : UiState

sealed interface FashionCatalogEvent : UiEvent {
    /**
     * Экран вернулся на передний план: корзину могли изменить на её экране,
     * а бейдж обязан это показать.
     */
    data object ScreenResumed : FashionCatalogEvent

    data object Refreshed : FashionCatalogEvent
    data object Retry : FashionCatalogEvent
    data object CategoriesRetry : FashionCatalogEvent
    data object LoadMore : FashionCatalogEvent

    /** `null` — «все категории»; повторный тап по выбранной снимает фильтр. */
    data class CategorySelected(val categoryId: String?) : FashionCatalogEvent

    data class ProductClicked(val productId: String) : FashionCatalogEvent
    data object CartClicked : FashionCatalogEvent
}

sealed interface FashionCatalogEffect : UiEffect {
    data class OpenProduct(val productId: String) : FashionCatalogEffect
    data object OpenCart : FashionCatalogEffect
}
