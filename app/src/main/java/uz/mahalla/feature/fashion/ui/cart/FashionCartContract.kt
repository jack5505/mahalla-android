package uz.mahalla.feature.fashion.ui.cart

import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.fashion.domain.FashionCart

/**
 * Корзина одежды (issue #108) — серверная.
 *
 * @param pendingVariantId строка, по которой сейчас идёт запрос. Пока она
 * висит, остальные заблокированы: ответы приезжали бы на корзину, которой уже
 * нет (то же правило, что у устройств в профиле, issue #61).
 * @param confirmRemove строка, удаление которой человек подтверждает.
 * Хранится целиком: диалог называет вещь, а искать её в списке ради подписи —
 * лишний повод разойтись с тем, что нажали.
 * @param actionFailure отказ изменения — отдельно от [cart]: корзина уже на
 * экране, и прятать её из-за неудавшейся кнопки незачем.
 */
data class FashionCartState(
    val cart: ScreenState<FashionCart> = ScreenState.Loading,
    val isRefreshing: Boolean = false,
    val pendingVariantId: String? = null,
    val confirmRemove: String? = null,
    val actionFailure: ApiFailure? = null,
) : UiState {
    val content: FashionCart? get() = (cart as? ScreenState.Content)?.data
}

sealed interface FashionCartEvent : UiEvent {
    /**
     * Экран вернулся на передний план: корзину могли пополнить с карточки
     * товара, а строку — забрать в заказ на другом устройстве.
     */
    data object ScreenResumed : FashionCartEvent

    data object Refreshed : FashionCartEvent
    data object Retry : FashionCartEvent

    data class QuantityChanged(val variantId: String, val quantity: Int) : FashionCartEvent
    data class RemoveRequested(val variantId: String) : FashionCartEvent
    data object RemoveDismissed : FashionCartEvent
    data object RemoveConfirmed : FashionCartEvent

    data class CheckoutClicked(val storeId: String) : FashionCartEvent
}

sealed interface FashionCartEffect : UiEffect {
    data class OpenCheckout(val storeId: String) : FashionCartEffect
}
