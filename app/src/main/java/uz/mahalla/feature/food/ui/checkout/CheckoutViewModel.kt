package uz.mahalla.feature.food.ui.checkout

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.analytics.AnalyticsEvents
import uz.mahalla.core.analytics.AnalyticsTracker
import uz.mahalla.core.analytics.AnalyticsVertical
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.feature.food.data.CartRepository
import uz.mahalla.feature.food.data.DeliveryFeeRepository
import uz.mahalla.feature.food.data.OrderRepository
import uz.mahalla.feature.food.domain.Cart
import uz.mahalla.feature.food.domain.CartCalculator
import uz.mahalla.feature.food.domain.CheckoutForm
import uz.mahalla.feature.food.domain.CheckoutValidator
import uz.mahalla.feature.food.domain.DeliveryMethod
import uz.mahalla.feature.food.ui.DeliveryFeeLoader
import uz.mahalla.feature.role.data.RoleRepository
import uz.mahalla.feature.wallet.domain.IdempotencyKey
import uz.mahalla.navigation.CheckoutRoute
import javax.inject.Inject

/**
 * Оформление заказа (эпик 5.3).
 *
 * Оплата — только наличные (issue #334, релиз только `CASH`): бэкенд `WALLET`
 * отклоняет, поэтому баланс кошелька здесь больше не запрашивается и оплата
 * не подтверждается шторкой — заказ уходит в сеть сразу.
 *
 * Стоимость доставки (issue #179) запрашивается по сумме позиций и только при
 * доставке: у самовывоза её в заказе нет, и строка исчезает вместе с ней.
 * Итог при этом остаётся оценкой — окончательные суммы называет сервер в
 * ответе о созданном заказе.
 */
@HiltViewModel
class CheckoutViewModel @Inject constructor(
    private val cartRepository: CartRepository,
    private val orderRepository: OrderRepository,
    private val roleRepository: RoleRepository,
    deliveryFeeRepository: DeliveryFeeRepository,
    private val analytics: AnalyticsTracker,
    savedStateHandle: SavedStateHandle,
) : MviViewModel<CheckoutState, CheckoutEvent, CheckoutEffect>(CheckoutState()) {

    private val placeId: String = savedStateHandle.toRoute<CheckoutRoute>().placeId

    /** Черновик корзины на момент открытия — из него собирается заказ. */
    private var cart: Cart = Cart(placeId = placeId, placeName = "")

    private val deliveryFee = DeliveryFeeLoader(
        repository = deliveryFeeRepository,
        scope = viewModelScope,
        onFee = { fee -> updateState { copy(deliverySum = fee).revalidated() } },
    )

    /**
     * Ключ идемпотентности заказа: двойное нажатие «оформить» не должно
     * создавать два заказа, а повтор после отказа — это тот же заказ, а не
     * новый.
     */
    private var cashIdempotencyKey: String? = null

    init {
        updateState { copy(placeId = placeId).revalidated() }
        viewModelScope.launch {
            cartRepository.cart(placeId).collect { updated ->
                cart = updated
                updateState { withCart(updated).revalidated() }
                refreshDeliveryFee()
            }
        }
        prefillAddress()
    }

    override fun onEvent(event: CheckoutEvent) {
        when (event) {
            is CheckoutEvent.MethodSelected -> {
                updateForm { copy(method = event.method) }
                // Способ получения решает, нужна ли доставка вообще: при
                // переключении на самовывоз цена не просто перестаёт быть
                // нужной — её нельзя оставлять в итоге.
                refreshDeliveryFee()
            }
            is CheckoutEvent.AddressChanged -> updateForm { copy(address = event.address) }

            CheckoutEvent.SubmitClicked -> submit()

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
     * Запрос стоимости доставки: нужна и сумма позиций, и способ получения,
     * поэтому зовётся и на изменение корзины, и на переключение способа.
     * Дальше решает [DeliveryFeeLoader] — та же сумма второй раз не
     * запрашивается.
     */
    private fun refreshDeliveryFee() {
        val state = currentState
        deliveryFee.refresh(
            itemsSum = state.totals.subtotalSum,
            needed = state.form.method == DeliveryMethod.Delivery,
        )
    }

    /**
     * Итог и ошибки считаются вместе: от суммы зависит проверка адреса, и
     * считать их по отдельности значит однажды показать итог, не совпадающий с
     * причиной отказа.
     *
     * Доставка входит в сумму, когда её назвал `food/delivery-fee` и заказ
     * действительно доставляют (issue #179): именно эту сумму человек и увидит
     * при оплате курьеру.
     */
    private fun CheckoutState.revalidated(): CheckoutState {
        val delivery = if (form.method == DeliveryMethod.Delivery) deliverySum ?: 0 else 0
        val totals = CartCalculator.totals(lines, deliverySum = delivery)
        return copy(
            totals = totals,
            errors = CheckoutValidator.validate(
                form = form,
                totals = totals,
                cartIsEmpty = lines.isEmpty(),
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

    /** Оформление. Оплата только наличными — заказ уходит в сеть сразу, без подтверждения. */
    private fun submit() {
        val state = currentState.revalidated()
        if (state.errors.isNotEmpty()) {
            updateState { state.copy(validationShown = true) }
            return
        }
        if (state.isSubmitting) return

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
                    onOrderCreated(result.data)
                }
            }
        }
    }

    private fun onOrderCreated(orderId: String) {
        analytics.track(AnalyticsEvents.ordered(placeId, AnalyticsVertical.Food))
        emitEffect(CheckoutEffect.OrderCreated(orderId))
    }
}
