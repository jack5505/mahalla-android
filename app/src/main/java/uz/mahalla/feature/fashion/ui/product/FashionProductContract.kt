package uz.mahalla.feature.fashion.ui.product

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.fashion.domain.FashionProductDetail
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
 */
data class FashionProductState(
    val product: ScreenState<FashionProductDetail> = ScreenState.Loading,
    val selectedVariantId: String? = null,
    val isAdding: Boolean = false,
    val added: Boolean = false,
    val addFailure: ApiFailure? = null,
) : UiState {
    val detail: FashionProductDetail? get() = (product as? ScreenState.Content)?.data

    val selectedVariant: ProductVariant? get() = detail?.variant(selectedVariantId)

    /** Выбранный цвет — тот, которому принадлежит выбранный вариант. */
    val selectedColor: String? get() = selectedVariant?.colorName

    /** Кнопка активна только когда есть что класть в корзину. */
    val canAddToCart: Boolean
        get() = !isAdding && selectedVariant?.isOrderable == true
}

sealed interface FashionProductEvent : UiEvent {
    data object Retry : FashionProductEvent
    data class ColorSelected(val color: String) : FashionProductEvent
    data class VariantSelected(val variantId: String) : FashionProductEvent
    data object AddToCartClicked : FashionProductEvent
    data object CartClicked : FashionProductEvent
}

sealed interface FashionProductEffect : UiEffect {
    data object OpenCart : FashionProductEffect
}
