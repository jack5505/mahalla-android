package uz.mahalla.feature.fashion.ui.checkout

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.feature.fashion.data.FashionCartRepository
import uz.mahalla.feature.fashion.data.FashionOrderRepository
import uz.mahalla.feature.fashion.domain.FashionCartItem
import uz.mahalla.feature.fashion.domain.FashionCartStore
import uz.mahalla.feature.food.domain.CartTotals
import uz.mahalla.feature.food.domain.CheckoutForm
import uz.mahalla.feature.food.domain.CheckoutValidator
import uz.mahalla.feature.role.data.RoleRepository
import uz.mahalla.feature.wallet.data.WalletRepository
import uz.mahalla.navigation.FashionArgs
import javax.inject.Inject

/**
 * Оформление заказа одежды (issue #108).
 *
 * Состав заказа читается **у сервера**, а не приезжает с экрана корзины:
 * корзина серверная, и между экранами она могла измениться — оформлять то,
 * чего там уже нет, значит получить отказ вместо заказа.
 *
 * Доставка в итог не входит: сколько она стоит, бэкенд сообщает только в
 * ответе о созданном заказе (`OrderView.deliveryAmount`) — до оформления её
 * не знает никто. То же самое было решено для «Еды» (issue #9).
 */
@HiltViewModel
class FashionCheckoutViewModel @Inject constructor(
    private val cartRepository: FashionCartRepository,
    private val orderRepository: FashionOrderRepository,
    private val walletRepository: WalletRepository,
    private val roleRepository: RoleRepository,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<FashionCheckoutState, FashionCheckoutEvent, FashionCheckoutEffect>(
    FashionCheckoutState(),
) {

    private val storeId: String = savedStateHandle[FashionArgs.STORE_ID] ?: ""

    init {
        val store = storeId
        updateState { copy(storeId = store).revalidated() }
        loadCart()
        loadBalance()
        prefillAddress()
    }

    override fun onEvent(event: FashionCheckoutEvent) {
        when (event) {
            FashionCheckoutEvent.Retry -> loadCart()
            is FashionCheckoutEvent.MethodSelected -> updateForm { copy(method = event.method) }
            is FashionCheckoutEvent.AddressChanged -> updateForm { copy(address = event.address) }
            is FashionCheckoutEvent.PaymentSelected -> updateForm { copy(payment = event.payment) }
            FashionCheckoutEvent.SubmitClicked -> submit()
            FashionCheckoutEvent.TopUpClicked -> emitEffect(FashionCheckoutEffect.OpenWallet)
            FashionCheckoutEvent.OrdersClicked -> emitEffect(FashionCheckoutEffect.OpenOrders)
        }
    }

    private fun updateForm(transform: CheckoutForm.() -> CheckoutForm) {
        updateState { copy(form = form.transform(), submitError = null).revalidated() }
    }

    private fun loadCart() {
        updateState { copy(loadFailure = null) }
        viewModelScope.launch {
            when (val result = cartRepository.cart()) {
                is ApiResult.Failure -> updateState {
                    copy(isLoaded = true, loadFailure = result.failure).revalidated()
                }

                is ApiResult.Success -> updateState {
                    copy(
                        // Строки только этого магазина: остальное уедет
                        // отдельными заказами.
                        items = result.data.store(storeId)?.items.orEmpty(),
                        isLoaded = true,
                    ).revalidated()
                }
            }
        }
    }

    /**
     * Адрес доставки из анкеты покупателя (issue #84): набирать его заново при
     * каждом заказе незачем.
     *
     * Подставляется только в пустое поле: чтение асинхронное, и человек может
     * начать печатать раньше, чем оно закончится, — затирать набранное нельзя.
     */
    private fun prefillAddress() {
        viewModelScope.launch {
            val saved = roleRepository.current().customer.address
            if (saved.isBlank()) return@launch
            updateState {
                if (form.address.isBlank()) {
                    copy(form = form.copy(address = saved)).revalidated()
                } else {
                    this
                }
            }
        }
    }

    private fun loadBalance() {
        viewModelScope.launch {
            when (val result = walletRepository.wallet()) {
                // Баланс неизвестен — считаем его достаточным: отказать в
                // оформлении из-за неотвеченного запроса хуже, чем получить
                // отказ на сервере, который всё равно проверит деньги.
                is ApiResult.Failure -> updateState {
                    copy(balanceKnown = false, walletBalanceSum = Long.MAX_VALUE).revalidated()
                }

                // Сравнивать нужно именно с «доступно»: заморозка под другую
                // незавершённую операцию потратить себя не даст (issue #62).
                is ApiResult.Success -> updateState {
                    copy(balanceKnown = true, walletBalanceSum = result.data.availableSum)
                        .revalidated()
                }
            }
        }
    }

    /**
     * Итог и ошибки считаются вместе: от суммы зависит проверка баланса, и по
     * отдельности они однажды разойдутся — на экране окажется итог, не
     * совпадающий с причиной отказа.
     */
    private fun FashionCheckoutState.revalidated(): FashionCheckoutState {
        val totals = CartTotals(subtotalSum = items.sumOf(FashionCartItem::totalSum))
        return copy(
            totals = totals,
            errors = CheckoutValidator.validate(
                form = form,
                totals = totals,
                cartIsEmpty = items.isEmpty(),
                walletBalanceSum = walletBalanceSum,
            ),
        )
    }

    /**
     * Оформление. Второй тап, пока идёт запрос или пока заказ уже создан, не
     * заводит второй заказ: в отличие от корзины, здесь платой за ошибку
     * будут две посылки.
     */
    private fun submit() {
        val state = currentState.revalidated()
        if (state.orderCreated || state.isSubmitting) return
        if (state.errors.isNotEmpty()) {
            updateState { state.copy(validationShown = true) }
            return
        }

        updateState { copy(isSubmitting = true, submitError = null) }
        viewModelScope.launch {
            val store = FashionCartStore(storeId = storeId, items = state.items)
            when (val result = orderRepository.create(store, state.form)) {
                is ApiResult.Failure -> updateState {
                    copy(isSubmitting = false, submitError = result.failure)
                }

                is ApiResult.Success -> updateState {
                    copy(isSubmitting = false, orderCreated = true)
                }
            }
        }
    }
}
