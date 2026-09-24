package uz.mahalla.feature.fashion.ui.product

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.fashion.data.FashionCartRepository
import uz.mahalla.feature.fashion.data.FashionRepository
import uz.mahalla.feature.fashion.domain.FashionProductDetail
import uz.mahalla.feature.fashion.domain.NewFashionVariantDraft
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
    private val isOwner: Boolean = savedStateHandle[FashionArgs.IS_OWNER] ?: false

    private var createJob: Job? = null

    init {
        val owner = isOwner
        updateState { copy(isOwner = owner) }
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

            FashionProductEvent.AddVariantClicked -> onAddVariantClicked()
            FashionProductEvent.CreateFormDismissed -> {
                // Отменяет и незавершённый запрос — та же причина, что у
                // формы товара в витрине (issue #280).
                createJob?.cancel()
                updateState { copy(createForm = null) }
            }
            is FashionProductEvent.CreateColorNameChanged ->
                updateCreateDraft { withColorName(event.value) }
            is FashionProductEvent.CreateColorHexChanged ->
                updateCreateDraft { withColorHex(event.value) }
            is FashionProductEvent.CreateSizeChanged -> updateCreateDraft { withSize(event.value) }
            is FashionProductEvent.CreateSkuChanged -> updateCreateDraft { withSku(event.value) }
            is FashionProductEvent.CreatePriceChanged -> updateCreateDraft { withPrice(event.value) }
            is FashionProductEvent.CreateStockChanged -> updateCreateDraft { withStock(event.value) }
            FashionProductEvent.CreateSubmitted -> submitCreate()
        }
    }

    /** Кнопка скрыта не-владельцу самим экраном — проверка здесь на всякий случай. */
    private fun onAddVariantClicked() {
        if (!currentState.isOwner) return
        createJob?.cancel()
        updateState { copy(createForm = NewFashionVariantFormState()) }
    }

    private inline fun updateCreateDraft(
        crossinline transform: NewFashionVariantDraft.() -> NewFashionVariantDraft,
    ) {
        updateState {
            copy(
                createForm = createForm?.let {
                    it.copy(draft = it.draft.transform(), failure = null)
                },
            )
        }
    }

    /**
     * Успех перечитывает карточку целиком (тот же приём, что у товара в
     * витрине) — новый вариант должен появиться среди цветов и размеров, а
     * не собираться из черновика на клиенте.
     */
    private fun submitCreate() {
        val form = currentState.createForm ?: return
        if (form.submitting) return
        if (!form.draft.canSubmit) {
            updateState { copy(createForm = form.copy(submitAttempted = true)) }
            return
        }

        updateState { copy(createForm = form.copy(submitting = true, failure = null)) }
        createJob = viewModelScope.launch {
            when (val result = repository.createVariant(productId, form.draft)) {
                is ApiResult.Failure -> updateState {
                    copy(createForm = createForm?.copy(submitting = false, failure = result.failure))
                }

                is ApiResult.Success -> {
                    updateState { copy(createForm = null) }
                    load()
                }
            }
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
