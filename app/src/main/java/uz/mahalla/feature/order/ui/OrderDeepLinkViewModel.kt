package uz.mahalla.feature.order.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.navigation.toRoute
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.core.ui.state.map
import uz.mahalla.core.ui.state.toScreenState
import uz.mahalla.feature.activity.domain.ActivityKind
import uz.mahalla.feature.order.domain.OrderVerticalResolver
import uz.mahalla.navigation.OrderDeepLinkRoute

/**
 * Резолвер вертикали заказа для deep link'а (issue #343): `mahalla://order/{id}`
 * не знает, «Еда» это, «Одежда» или «Аптека», а экран у каждой вертикали свой
 * (или пока нет вовсе).
 */
sealed interface OrderDeepLinkDestination {
    data class Food(val orderId: String) : OrderDeepLinkDestination
    data object Clothing : OrderDeepLinkDestination

    /** Вертикаль без своего экрана заказа (аптека и всё, чего клиент ещё не знает). */
    data object Activities : OrderDeepLinkDestination
}

@HiltViewModel
class OrderDeepLinkViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val resolver: OrderVerticalResolver,
) : ViewModel() {

    private val orderId: String = savedStateHandle.toRoute<OrderDeepLinkRoute>().orderId

    private val _state = MutableStateFlow<ScreenState<OrderDeepLinkDestination>>(ScreenState.Loading)
    val state: StateFlow<ScreenState<OrderDeepLinkDestination>> = _state.asStateFlow()

    init {
        resolve()
    }

    fun retry() = resolve()

    private fun resolve() {
        _state.value = ScreenState.Loading
        viewModelScope.launch {
            _state.value = resolver.resolve(orderId).toScreenState().map(::toDestination)
        }
    }

    private fun toDestination(kind: ActivityKind): OrderDeepLinkDestination = when (kind) {
        ActivityKind.FoodOrder -> OrderDeepLinkDestination.Food(orderId)
        ActivityKind.ClothingOrder -> OrderDeepLinkDestination.Clothing
        // Аптека (issue #343) и всё остальное — своего экрана заказа нет,
        // общий список «мои активности» хотя бы найдёт заказ по id.
        else -> OrderDeepLinkDestination.Activities
    }
}
