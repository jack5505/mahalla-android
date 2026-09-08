package uz.mahalla.feature.food.ui.order

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.food.data.OrderRepository
import uz.mahalla.feature.food.domain.Order
import uz.mahalla.feature.food.domain.OrderStatusFlow
import uz.mahalla.navigation.OrderStatusRoute
import javax.inject.Inject

/**
 * Статус заказа (эпик 5.4).
 *
 * Статус опрашивается раз в [POLL_INTERVAL_MS] и опрос сам прекращается на
 * финальном статусе: push'ей у приложения пока нет, а вечный поллинг
 * завершённого заказа — это разряженная батарея за ничто.
 *
 * Ошибка опроса не стирает уже показанный заказ: на плохой связи экран должен
 * продолжать показывать последнее известное состояние, а не пустой retry.
 */
@HiltViewModel
class OrderStatusViewModel @Inject constructor(
    private val repository: OrderRepository,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<OrderStatusState, OrderStatusEvent, OrderStatusEffect>(OrderStatusState()) {

    private val orderId: String = savedStateHandle.toRoute<OrderStatusRoute>().orderId

    private var pollJob: Job? = null

    init {
        load()
    }

    override fun onEvent(event: OrderStatusEvent) {
        when (event) {
            OrderStatusEvent.Retry -> load()

            // Экран в фоне не опрашивается: статус всё равно некому показать,
            // а батарея расходуется. При возврате опрос продолжается — если
            // заказ ещё не в финальном статусе.
            OrderStatusEvent.ScreenStarted -> {
                // Первый ON_START приходит сразу после подписки, когда опрос
                // уже запущен из init — второй запрос за той же секундой не
                // нужен.
                val status = currentState.data?.status
                val finished = status != null && OrderStatusFlow.isFinal(status)
                if (pollJob?.isActive != true && !finished) {
                    load(showLoading = currentState.data == null)
                }
            }

            OrderStatusEvent.ScreenStopped -> pollJob?.cancel()

            OrderStatusEvent.CancelClicked -> updateState {
                copy(cancelConfirmVisible = true, cancelFailure = null)
            }

            OrderStatusEvent.CancelDismissed -> updateState { copy(cancelConfirmVisible = false) }

            OrderStatusEvent.CancelConfirmed -> cancel()

            OrderStatusEvent.RepeatClicked -> repeat()

            OrderStatusEvent.BackClicked -> emitEffect(OrderStatusEffect.NavigateBack)
        }
    }

    private fun load(showLoading: Boolean = true) {
        pollJob?.cancel()
        if (showLoading) updateState { copy(order = ScreenState.Loading) }
        pollJob = viewModelScope.launch {
            while (true) {
                when (val result = repository.order(orderId)) {
                    is ApiResult.Failure -> {
                        // Заказ уже показан — ошибку опроса игнорируем и ждём
                        // следующей попытки: моргать экраном на каждом обрыве
                        // связи хуже, чем показать статус минутной давности.
                        if (currentState.data == null) {
                            updateState { copy(order = ScreenState.Error(result.failure)) }
                            return@launch
                        }
                    }

                    is ApiResult.Success -> {
                        updateState { copy(order = ScreenState.Content(result.data)) }
                        if (OrderStatusFlow.isFinal(result.data.status)) return@launch
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun cancel() {
        val order = currentState.data ?: return
        if (!OrderStatusFlow.canCancel(order.status)) return

        updateState {
            copy(isCancelling = true, cancelConfirmVisible = false, cancelFailure = null)
        }
        viewModelScope.launch {
            when (val result = repository.cancel(orderId)) {
                is ApiResult.Failure -> updateState {
                    copy(isCancelling = false, cancelFailure = result.failure)
                }

                // Новое состояние заказа читаем у сервера, а не выводим сами:
                // ответ на отмену бэкенд отдаёт схемой, которой в приложении
                // нет (см. `OrderRepository`). Загрузка идёт без скелетона —
                // заказ уже на экране, и моргать им незачем.
                is ApiResult.Success -> {
                    updateState { copy(isCancelling = false) }
                    load(showLoading = false)
                }
            }
        }
    }

    private fun repeat() {
        val order: Order = currentState.data ?: return
        if (!OrderStatusFlow.canRepeat(order.status)) return
        updateState { copy(isRepeating = true, repeatFailed = false) }
        viewModelScope.launch {
            // Уходим в корзину только если она действительно собрана: пустая
            // корзина после «повторить» выглядит как потерянный заказ.
            val lines = repository.repeat(order)
            updateState { copy(isRepeating = false, repeatFailed = lines == null) }
            if (lines != null) emitEffect(OrderStatusEffect.OpenCart(order.placeId))
        }
    }

    private companion object {
        /**
         * Пять секунд — компромисс между «статус меняется на глазах» и
         * «телефон греется». Push'и заменят опрос, когда появятся.
         */
        const val POLL_INTERVAL_MS = 5_000L
    }
}
