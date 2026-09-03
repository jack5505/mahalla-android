package uz.mahalla.feature.food.ui.checkout

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.feature.food.data.CartRepository
import uz.mahalla.feature.food.data.OrderRepository
import uz.mahalla.feature.food.domain.Cart
import uz.mahalla.feature.food.domain.CartCalculator
import uz.mahalla.feature.food.domain.CheckoutForm
import uz.mahalla.feature.food.domain.CheckoutValidator
import uz.mahalla.feature.role.data.RoleRepository
import uz.mahalla.feature.wallet.data.WalletRepository
import uz.mahalla.navigation.CheckoutRoute
import javax.inject.Inject

/**
 * Оформление заказа (эпик 5.3).
 *
 * Баланс кошелька запрашивается один раз при открытии. Не приехал — оплату
 * кошельком не блокируем: отказать в оформлении из-за неотвеченного запроса
 * хуже, чем получить отказ на стороне сервера, который всё равно проверит
 * деньги повторно.
 */
@HiltViewModel
class CheckoutViewModel @Inject constructor(
    private val cartRepository: CartRepository,
    private val orderRepository: OrderRepository,
    private val walletRepository: WalletRepository,
    private val roleRepository: RoleRepository,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<CheckoutState, CheckoutEvent, CheckoutEffect>(CheckoutState()) {

    private val placeId: String = savedStateHandle.toRoute<CheckoutRoute>().placeId

    /** Черновик корзины на момент открытия — из него собирается заказ. */
    private var cart: Cart = Cart(placeId = placeId, placeName = "")

    init {
        updateState { copy(placeId = placeId).revalidated() }
        viewModelScope.launch {
            cartRepository.cart(placeId).collect { updated ->
                cart = updated
                updateState { withCart(updated).revalidated() }
            }
        }
        loadBalance()
        prefillAddress()
    }

    override fun onEvent(event: CheckoutEvent) {
        when (event) {
            is CheckoutEvent.MethodSelected -> updateForm { copy(method = event.method) }
            is CheckoutEvent.AddressChanged -> updateForm { copy(address = event.address) }
            is CheckoutEvent.PaymentSelected -> updateForm { copy(payment = event.payment) }

            CheckoutEvent.SubmitClicked -> submit()
            CheckoutEvent.TopUpClicked -> emitEffect(CheckoutEffect.OpenWallet)
            CheckoutEvent.BackClicked -> emitEffect(CheckoutEffect.NavigateBack)
        }
    }

    private fun updateForm(transform: CheckoutForm.() -> CheckoutForm) {
        updateState { copy(form = form.transform(), submitError = null).revalidated() }
    }

    private fun CheckoutState.withCart(cart: Cart): CheckoutState = copy(
        placeName = cart.placeName.takeIf(String::isNotBlank) ?: placeName,
        lines = cart.lines,
        isLoaded = true,
    )

    /**
     * Итог и ошибки считаются вместе: от суммы зависит проверка баланса, и
     * считать их по отдельности значит однажды показать итог, не совпадающий с
     * причиной отказа.
     *
     * Доставка в сумму не входит: сколько она стоит, бэкенд сообщает только в
     * ответе о созданном заказе — до оформления её не знает никто.
     */
    private fun CheckoutState.revalidated(): CheckoutState {
        val totals = CartCalculator.totals(lines)
        return copy(
            totals = totals,
            errors = CheckoutValidator.validate(
                form = form,
                totals = totals,
                cartIsEmpty = lines.isEmpty(),
                walletBalanceSum = walletBalanceSum,
            ),
        )
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
                is ApiResult.Failure -> updateState {
                    // Баланс неизвестен — считаем его достаточным: решающее
                    // слово всё равно за сервером при создании заказа.
                    copy(balanceKnown = false, walletBalanceSum = Long.MAX_VALUE).revalidated()
                }

                // Сравнивать с суммой заказа нужно именно «доступно»:
                // заморозка под другую незавершённую операцию потратить себя
                // не даст (issue #62).
                is ApiResult.Success -> updateState {
                    copy(
                        balanceKnown = true,
                        walletBalanceSum = result.data.availableSum,
                    ).revalidated()
                }
            }
        }
    }

    private fun submit() {
        val state = currentState.revalidated()
        if (state.errors.isNotEmpty()) {
            updateState { state.copy(validationShown = true) }
            return
        }
        if (state.isSubmitting) return

        updateState { copy(isSubmitting = true, submitError = null) }
        viewModelScope.launch {
            when (val result = orderRepository.create(cart.copy(lines = state.lines), state.form)) {
                is ApiResult.Failure -> updateState {
                    copy(isSubmitting = false, submitError = result.failure)
                }

                is ApiResult.Success -> {
                    updateState { copy(isSubmitting = false) }
                    emitEffect(CheckoutEffect.OrderCreated(result.data))
                }
            }
        }
    }
}
