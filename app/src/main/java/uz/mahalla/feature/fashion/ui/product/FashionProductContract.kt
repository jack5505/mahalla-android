package uz.mahalla.feature.fashion.ui.product

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.fashion.domain.FashionProductDetail
import uz.mahalla.feature.fashion.domain.NewFashionVariantDraft
import uz.mahalla.feature.fashion.domain.ProductVariant

/**
 * Карточка товара (issue #108): цвет → размер → «в корзину».
 *
 * @param selectedVariantId выбранный вариант. Хранится идентификатором, а не
 * объектом: карточка перезагружается (остатки живые), и объект из прошлого
 * ответа разошёлся бы с новым.
 * @param added строка, которую сервер положил в корзину. Пока она есть, экран
 * показывает подтверждение с путём в корзину: молчаливый успех читается как
 * «ничего не произошло» (issue #49).
 * @param addFailure отказ добавления — отдельно от [product]: карточка уже на
 * экране, и прятать её из-за неудавшейся кнопки незачем.
 * @param isOwner владелец или менеджер магазина (issue #280) — приезжает уже
 * выставленным с витрины, откуда открыта карточка (тот же приём, что у
 * [uz.mahalla.feature.fashion.ui.catalog.FashionCatalogState.isOwner]).
 */
data class FashionProductState(
    val product: ScreenState<FashionProductDetail> = ScreenState.Loading,
    val selectedVariantId: String? = null,
    val isAdding: Boolean = false,
    val added: Boolean = false,
    val addFailure: ApiFailure? = null,
    val isOwner: Boolean = false,
    val createForm: NewFashionVariantFormState? = null,
) : UiState {
    val detail: FashionProductDetail? get() = (product as? ScreenState.Content)?.data

    val selectedVariant: ProductVariant? get() = detail?.variant(selectedVariantId)

    /** Выбранный цвет — тот, которому принадлежит выбранный вариант. */
    val selectedColor: String? get() = selectedVariant?.colorName

    /** Кнопка активна только когда есть что класть в корзину. */
    val canAddToCart: Boolean
        get() = !isAdding && selectedVariant?.isOrderable == true
}

/** Форма нового варианта — размер/цвет (issue #280). */
data class NewFashionVariantFormState(
    val draft: NewFashionVariantDraft = NewFashionVariantDraft(),
    /** Ошибки полей показываются только после первой попытки сохранить —
     * тот же приём, что у [uz.mahalla.feature.pharmacy.ui.NewProductFormState]. */
    val submitAttempted: Boolean = false,
    val submitting: Boolean = false,
    val failure: ApiFailure? = null,
)

sealed interface FashionProductEvent : UiEvent {
    data object Retry : FashionProductEvent
    data class ColorSelected(val color: String) : FashionProductEvent
    data class VariantSelected(val variantId: String) : FashionProductEvent
    data object AddToCartClicked : FashionProductEvent
    data object CartClicked : FashionProductEvent

    // Новый вариант (issue #280) — доступно только владельцу/менеджеру.
    data object AddVariantClicked : FashionProductEvent
    data object CreateFormDismissed : FashionProductEvent
    data class CreateColorNameChanged(val value: String) : FashionProductEvent
    data class CreateColorHexChanged(val value: String) : FashionProductEvent
    data class CreateSizeChanged(val value: String) : FashionProductEvent
    data class CreateSkuChanged(val value: String) : FashionProductEvent
    data class CreatePriceChanged(val value: String) : FashionProductEvent
    data class CreateStockChanged(val value: String) : FashionProductEvent
    data object CreateSubmitted : FashionProductEvent
}

sealed interface FashionProductEffect : UiEffect {
    data object OpenCart : FashionProductEffect
}
