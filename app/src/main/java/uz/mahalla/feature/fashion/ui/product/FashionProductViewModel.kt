package uz.mahalla.feature.fashion.ui.product

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.fashion.data.FashionCartRepository
import uz.mahalla.feature.fashion.data.FashionRepository
import uz.mahalla.feature.fashion.domain.FashionProductDetail
import uz.mahalla.feature.fashion.domain.ProductVariant
import uz.mahalla.feature.fashion.domain.VariantSelection
import uz.mahalla.navigation.FashionArgs
import javax.inject.Inject

/**
 * Карточка товара одежды (issue #108).
 *
 * В корзину кладётся **вариант** (размер конкретного цвета), а не товар:
 * ключ строки серверной корзины — `variantId`. Поэтому кнопка выключена, пока
 * вариант не выбран или его нет в наличии: «в корзину», которое ответит
 * ошибкой, — худший способ сообщить, что размер кончился.
 */
@HiltViewModel
class FashionProductViewModel @Inject constructor(
    private val repository: FashionRepository,
    private val cartRepository: FashionCartRepository,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<FashionProductState, FashionProductEvent, FashionProductEffect>(
    FashionProductState(),
) {

    private val productId: String = savedStateHandle[FashionArgs.PRODUCT_ID] ?: ""

    init {
        load()
    }

    override fun onEvent(event: FashionProductEvent) {
        when (event) {
            FashionProductEvent.Retry -> load()

            is FashionProductEvent.ColorSelected -> select { detail, current ->
                VariantSelection.selectColor(detail, event.color, current)
            }

            is FashionProductEvent.VariantSelected -> select { detail, current ->
                VariantSelection.selectVariant(detail, event.variantId, current)
            }

            FashionProductEvent.AddToCartClicked -> addToCart()
            FashionProductEvent.CartClicked -> emitEffect(FashionProductEffect.OpenCart)
        }
    }

    private fun load() {
        updateState { copy(product = ScreenState.Loading, addFailure = null, added = false) }
        viewModelScope.launch {
            when (val result = repository.product(productId)) {
                is ApiResult.Failure -> updateState {
                    copy(product = ScreenState.Error(result.failure))
                }

                is ApiResult.Success -> updateState {
                    copy(
                        product = ScreenState.Content(result.data),
                        selectedVariantId = VariantSelection.initial(result.data)?.id,
                    )
                }
            }
        }
    }

    /**
     * Смена выбора. Подтверждение «добавлено» при этом снимается: оно было про
     * прошлый вариант, и оставить его рядом с новым размером значит сказать,
     * что в корзине лежит не то, что там лежит.
     */
    private fun select(pick: (FashionProductDetail, ProductVariant?) -> ProductVariant?) {
        val detail = currentState.detail ?: return
        val next = pick(detail, currentState.selectedVariant) ?: return
        updateState { copy(selectedVariantId = next.id, added = false, addFailure = null) }
    }

    /**
     * В корзину. Второй тап, пока идёт запрос, не заводит вторую строку:
     * бэкенд сложил бы количества, и в корзине оказалось бы две вещи вместо
     * одной.
     */
    private fun addToCart() {
        val variant = currentState.selectedVariant?.takeIf(ProductVariant::isOrderable) ?: return
        if (currentState.isAdding) return

        updateState { copy(isAdding = true, addFailure = null, added = false) }
        viewModelScope.launch {
            when (val result = cartRepository.add(variant.id)) {
                is ApiResult.Failure -> updateState {
                    copy(isAdding = false, addFailure = result.failure)
                }

                is ApiResult.Success -> updateState { copy(isAdding = false, added = true) }
            }
        }
    }
}
