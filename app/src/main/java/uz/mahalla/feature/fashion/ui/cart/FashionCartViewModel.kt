package uz.mahalla.feature.fashion.ui.cart

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.isLoading
import uz.mahalla.feature.fashion.data.FashionCartRepository
import uz.mahalla.feature.fashion.domain.FashionCart
import uz.mahalla.feature.fashion.domain.FashionCartItem
import uz.mahalla.feature.fashion.domain.FashionCartRules
import javax.inject.Inject

/**
 * Корзина одежды (issue #108).
 *
 * Корзина живёт **на сервере**: офлайна нет, а значит нет и оптимистичного
 * пересчёта. Строка меняется только после того, как сервер подтвердил
 * изменение — речь о деньгах, и показать сумму, которой у бэкенда нет, значит
 * соврать о том, сколько человек заплатит.
 *
 * Правится при этом **строка на месте**, а не перезапрашивается вся корзина:
 * лишний круг запросов на каждый тап степпера ничего не уточняет — сервер уже
 * ответил, каким стало количество.
 */
@HiltViewModel
class FashionCartViewModel @Inject constructor(
    private val repository: FashionCartRepository,
) : MviViewModel<FashionCartState, FashionCartEvent, FashionCartEffect>(FashionCartState()) {

    init {
        load()
    }

    override fun onEvent(event: FashionCartEvent) {
        when (event) {
            // Пока идёт загрузка или изменение строки, перезапрашивать нечего:
            // ответ приедет на уже сменившееся состояние.
            FashionCartEvent.ScreenResumed -> {
                val state = currentState
                if (!state.cart.isLoading && !state.isRefreshing && state.pendingVariantId == null) {
                    load(showLoading = false)
                }
            }

            FashionCartEvent.Refreshed -> load(showLoading = false, refreshing = true)
            FashionCartEvent.Retry -> load()

            is FashionCartEvent.QuantityChanged -> changeQuantity(event.variantId, event.quantity)

            is FashionCartEvent.RemoveRequested -> updateState {
                copy(
                    confirmRemove = content?.item(event.variantId)?.variantId,
                    actionFailure = null,
                )
            }

            FashionCartEvent.RemoveDismissed -> updateState { copy(confirmRemove = null) }
            FashionCartEvent.RemoveConfirmed -> remove()

            is FashionCartEvent.CheckoutClicked ->
                emitEffect(FashionCartEffect.OpenCheckout(event.storeId))
        }
    }

    private fun load(showLoading: Boolean = true, refreshing: Boolean = false) {
        updateState {
            copy(
                cart = if (showLoading) ScreenState.Loading else cart,
                isRefreshing = refreshing,
                actionFailure = null,
            )
        }
        viewModelScope.launch {
            when (val result = repository.cart()) {
                is ApiResult.Failure -> updateState {
                    copy(cart = ScreenState.Error(result.failure), isRefreshing = false)
                }

                is ApiResult.Success -> updateState {
                    copy(cart = result.data.asScreenState(), isRefreshing = false)
                }
            }
        }
    }

    /**
     * Новое количество. Ниже единицы «−» превращается в удаление — у него своя
     * ручка (`DELETE`), а что сделает бэкенд с `quantity=0`, из контракта не
     * следует.
     */
    private fun changeQuantity(variantId: String, quantity: Int) {
        val item = currentState.content?.item(variantId) ?: return
        if (currentState.pendingVariantId != null) return
        if (FashionCartRules.isRemoval(quantity)) {
            updateState { copy(confirmRemove = variantId, actionFailure = null) }
            return
        }

        val next = FashionCartRules.normalize(quantity)
        if (next == item.quantity) return

        updateState { copy(pendingVariantId = variantId, actionFailure = null) }
        viewModelScope.launch {
            when (val result = repository.setQuantity(variantId, next)) {
                is ApiResult.Failure -> updateState {
                    copy(pendingVariantId = null, actionFailure = result.failure)
                }

                // Сумма строки пересчитывается из цены за единицу: серверную
                // (`totalPrice`) в ответе `PUT` не присылают вовсе, а оставить
                // прежнюю значит показать сумму от старого количества.
                is ApiResult.Success -> updateState {
                    copy(pendingVariantId = null).withItems { items ->
                        items.map { line ->
                            if (line.variantId == variantId) {
                                line.copy(quantity = next, serverTotalSum = null)
                            } else {
                                line
                            }
                        }
                    }
                }
            }
        }
    }

    private fun remove() {
        val variantId = currentState.confirmRemove ?: return
        if (currentState.pendingVariantId != null) return

        updateState { copy(confirmRemove = null, pendingVariantId = variantId, actionFailure = null) }
        viewModelScope.launch {
            when (val result = repository.remove(variantId)) {
                is ApiResult.Failure -> updateState {
                    copy(pendingVariantId = null, actionFailure = result.failure)
                }

                is ApiResult.Success -> updateState {
                    copy(pendingVariantId = null).withItems { items ->
                        items.filterNot { it.variantId == variantId }
                    }
                }
            }
        }
    }

    /**
     * Правка строк корзины на месте. Опустевшая корзина становится
     * [ScreenState.Empty], а не пустым содержимым: иначе экран показал бы
     * итог «0» и кнопку оформления над пустым списком.
     */
    private fun FashionCartState.withItems(
        transform: (List<FashionCartItem>) -> List<FashionCartItem>,
    ): FashionCartState {
        val current = content ?: return this
        return copy(cart = FashionCart(transform(current.items)).asScreenState())
    }
}

private fun FashionCart.asScreenState(): ScreenState<FashionCart> =
    if (isEmpty) ScreenState.Empty else ScreenState.Content(this)
