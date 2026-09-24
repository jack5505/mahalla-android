package uz.mahalla.feature.fashion.ui.catalog

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.fashion.domain.FashionCategory
import uz.mahalla.feature.fashion.domain.FashionProduct
import uz.mahalla.feature.fashion.domain.NewFashionProductDraft
import uz.mahalla.feature.fashion.domain.ProductGender

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
 * @param isOwner владелец или менеджер магазина (issue #280) — витриной
 * правит тот же круг людей, что переключает «открыто сейчас» в «Моих
 * заведениях», откуда сюда и попадают с этим флагом уже выставленным: у
 * товара магазина нет своего `ownerId`, чтобы проверить это на месте (тот же
 * приём, что у [uz.mahalla.feature.pharmacy.ui.PharmacyState.isOwner]).
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
    val isOwner: Boolean = false,
    val createForm: NewFashionProductFormState? = null,
) : UiState

/** Форма нового товара витрины (issue #280). */
data class NewFashionProductFormState(
    val draft: NewFashionProductDraft = NewFashionProductDraft(),
    /** Ошибки полей показываются только после первой попытки сохранить —
     * тот же приём, что у [uz.mahalla.feature.pharmacy.ui.NewProductFormState]. */
    val submitAttempted: Boolean = false,
    val submitting: Boolean = false,
    val failure: ApiFailure? = null,
)

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

    // Новый товар (issue #280) — доступно только владельцу/менеджеру.
    data object AddProductClicked : FashionCatalogEvent
    data object CreateFormDismissed : FashionCatalogEvent
    data class CreateNameChanged(val value: String) : FashionCatalogEvent
    data class CreateBrandChanged(val value: String) : FashionCatalogEvent
    data class CreateDescriptionChanged(val value: String) : FashionCatalogEvent
    data class CreateMaterialChanged(val value: String) : FashionCatalogEvent
    data class CreateCareInstructionsChanged(val value: String) : FashionCatalogEvent
    data class CreateSizeGuideChanged(val value: String) : FashionCatalogEvent
    data class CreateGenderChanged(val value: ProductGender) : FashionCatalogEvent
    data class CreateCategoryChanged(val categoryId: String?) : FashionCatalogEvent
    data class CreatePriceChanged(val value: String) : FashionCatalogEvent
    data object CreateSubmitted : FashionCatalogEvent
}

sealed interface FashionCatalogEffect : UiEffect {
    data class OpenProduct(val productId: String) : FashionCatalogEffect
    data object OpenCart : FashionCatalogEffect
}
