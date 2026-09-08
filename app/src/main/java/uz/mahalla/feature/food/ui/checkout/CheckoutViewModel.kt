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
import uz.mahalla.feature.food.domain.PaymentMethod
import uz.mahalla.feature.role.data.RoleRepository
import uz.mahalla.feature.wallet.data.WalletRepository
import uz.mahalla.feature.wallet.domain.IdempotencyKey
import uz.mahalla.feature.wallet.ui.pay.WalletPaymentFlow
import uz.mahalla.feature.wallet.ui.pay.WalletPaymentFlowFactory
import uz.mahalla.navigation.CheckoutRoute
import javax.inject.Inject

/**
 * Оформление заказа (эпик 5.3).
 *
 * Баланс кошелька запрашивается один раз при открытии. Не приехал — оплату
 * кошельком не блокируем: отказать в оформлении из-за неотвеченного запроса
 * хуже, чем получить отказ на стороне сервера, который всё равно проверит
 * деньги повторно.
 *
 * Оплата кошельком с задачи 8.3 (эпик #12) идёт через общий
 * [WalletPaymentFlow]: он перечитывает баланс, спрашивает PIN или биометрию,
 * отправляет **один** запрос на одно подтверждение и разбирает отказ. Наличные
 * им не проходят — подтверждать нечего, деньги кошелька не касаются.
 */
@HiltViewModel
class CheckoutViewModel @Inject constructor(
    private val cartRepository: CartRepository,
    private val orderRepository: OrderRepository,
    private val walletRepository: WalletRepository,
    private val roleRepository: RoleRepository,
    paymentFlows: WalletPaymentFlowFactory,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<CheckoutState, CheckoutEvent, CheckoutEffect>(CheckoutState()) {

    private val placeId: String = savedStateHandle.toRoute<CheckoutRoute>().placeId

    /** Черновик корзины на момент открытия — из него собирается заказ. */
    private var cart: Cart = Cart(placeId = placeId, placeName = "")

    /**
     * Оплата кошельком. Состав и форма читаются в момент отправки, а не при
     * создании flow: между нажатием «оформить» и подтверждением человек мог
     * поправить адрес.
     */
    private val payment: WalletPaymentFlow<String> = paymentFlows.create(viewModelScope) { key ->
        orderRepository.create(
            cart = cart.copy(lines = currentState.lines),
            form = currentState.form,
            idempotencyKey = key,
        )
    }

    /**
     * Ключ наличного заказа. Тоже нужен: двойное нажатие «оформить» создаёт два
     * заказа независимо от способа оплаты, а повтор после отказа — это тот же
     * заказ, а не новый.
     */
    private var cashIdempotencyKey: String? = null

    init {
        viewModelScope.launch {
            payment.state.collect { paymentState -> updateState { copy(payment = paymentState) } }
        }
        viewModelScope.launch {
            // Заказ создан и оплачен: черновик корзины уже почистил репозиторий.
            payment.paid.collect { orderId -> emitEffect(CheckoutEffect.OrderCreated(orderId)) }
        }
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

            // Пополнение открывается вместо шторки, а не под ней: возвращаться
            // человек будет на экран кошелька, и подтверждение прошлой попытки
            // за ним висеть не должно.
            CheckoutEvent.TopUpClicked -> {
                payment.dismiss()
                emitEffect(CheckoutEffect.OpenWallet)
            }

            CheckoutEvent.BackClicked -> emitEffect(CheckoutEffect.NavigateBack)

            is CheckoutEvent.PaymentPinChanged -> payment.pinChanged(event.pin)
            CheckoutEvent.PaymentBiometricConfirmed -> payment.biometricConfirmed()
            CheckoutEvent.PaymentBiometricRejected -> payment.biometricRejected()
            CheckoutEvent.PaymentRetried -> payment.retry()
            CheckoutEvent.PaymentDismissed -> payment.dismiss()
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

    /**
     * Оформление. Кошелёк уходит в подтверждение (8.3), наличные — сразу в
     * сеть: спрашивать PIN за заказ, который оплатят курьеру, незачем.
     */
    private fun submit() {
        val state = currentState.revalidated()
        if (state.errors.isNotEmpty()) {
            updateState { state.copy(validationShown = true) }
            return
        }
        if (state.isSubmitting || state.payment != null) return

        if (state.form.payment == PaymentMethod.Wallet) {
            updateState { copy(submitError = null) }
            payment.start(state.totals.totalSum)
            return
        }
        submitCash(state)
    }

    private fun submitCash(state: CheckoutState) {
        val key = cashIdempotencyKey ?: IdempotencyKey.random().also { cashIdempotencyKey = it }
        updateState { copy(isSubmitting = true, submitError = null) }
        viewModelScope.launch {
            val result = orderRepository.create(
                cart = cart.copy(lines = state.lines),
                form = state.form,
                idempotencyKey = key,
            )
            when (result) {
                is ApiResult.Failure -> updateState {
                    // Ключ остаётся: повтор — тот же заказ, а не второй.
                    copy(isSubmitting = false, submitError = result.failure)
                }

                is ApiResult.Success -> {
                    cashIdempotencyKey = null
                    updateState { copy(isSubmitting = false) }
                    emitEffect(CheckoutEffect.OrderCreated(result.data))
                }
            }
        }
    }
}
