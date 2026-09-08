package uz.mahalla.feature.food.ui.checkout

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.food.domain.Cart
import uz.mahalla.feature.food.domain.CheckoutError
import uz.mahalla.feature.food.domain.DeliveryMethod
import uz.mahalla.feature.food.domain.PaymentMethod
import uz.mahalla.feature.onboarding.domain.City
import uz.mahalla.feature.role.domain.CustomerForm
import uz.mahalla.data.security.PaymentConfirmationMethod
import uz.mahalla.feature.wallet.domain.Wallet
import uz.mahalla.feature.wallet.domain.WalletPaymentRejection
import uz.mahalla.feature.wallet.ui.pay.WalletPaymentFlowFactory
import uz.mahalla.testutil.FakeCartRepository
import uz.mahalla.testutil.FakeOrderRepository
import uz.mahalla.testutil.FakePaymentConfirmationPolicy
import uz.mahalla.testutil.FakePinStorage
import uz.mahalla.testutil.FakeRoleRepository
import uz.mahalla.testutil.FakeWalletRepository
import uz.mahalla.testutil.MainDispatcherRule
import uz.mahalla.testutil.cartLine

/**
 * Оформление заказа (эпик 5.3).
 *
 * Ни времени заказа, ни комментария в форме нет: `PlaceOrderRequest` бэкенда
 * их не принимает.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CheckoutViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val cartRepository = FakeCartRepository()
    private val orderRepository = FakeOrderRepository()
    private val walletRepository = FakeWalletRepository()
    private val roleRepository = FakeRoleRepository()
    private val pinStorage = FakePinStorage(initialPin = PIN)

    /**
     * По умолчанию подтверждать нечем: тестам про форму заказа важен сам
     * заказ, а не шторка оплаты. Подтверждение проверяется отдельными тестами
     * ниже и полностью — в `WalletPaymentFlowTest`.
     */
    private val confirmationPolicy = FakePaymentConfirmationPolicy()

    @Test
    fun `the total is the price of the items, without an invented delivery fee`() = runTest {
        // Стоимость доставки бэкенд называет только в ответе о созданном
        // заказе — до оформления её не знает никто.
        seed()
        val viewModel = viewModel()

        assertEquals(0L, viewModel.state.value.totals.deliverySum)
        assertEquals(60_000L, viewModel.state.value.totals.totalSum)

        viewModel.onEvent(CheckoutEvent.MethodSelected(DeliveryMethod.Pickup))

        assertEquals(60_000L, viewModel.state.value.totals.totalSum)
    }

    /**
     * Адрес доставки из анкеты покупателя (issue #84): набирать его заново при
     * каждом заказе незачем. Уже набранное при этом не затирается — чтение
     * анкеты асинхронное.
     */
    @Test
    fun `saved delivery address prefills the empty field`() = runTest {
        seed()
        roleRepository.saveCustomer(
            CustomerForm(fullName = "Jahongir", city = City.TASHKENT, address = "Chilonzor 12"),
        )

        assertEquals("Chilonzor 12", viewModel().state.value.form.address)
    }

    @Test
    fun `typed address is not overwritten by the saved one`() = runTest {
        seed()
        roleRepository.saveCustomer(
            CustomerForm(fullName = "Jahongir", city = City.TASHKENT, address = "Chilonzor 12"),
        )
        val viewModel = viewModel()

        viewModel.onEvent(CheckoutEvent.AddressChanged("Amir Temur 1"))

        assertEquals("Amir Temur 1", viewModel.state.value.form.address)
    }

    @Test
    fun `delivery without an address cannot be submitted`() = runTest {
        seed()
        val viewModel = viewModel()

        viewModel.onEvent(CheckoutEvent.SubmitClicked)

        assertTrue(viewModel.state.value.errors.contains(CheckoutError.AddressRequired))
        assertTrue(viewModel.state.value.validationShown)
        assertNull(orderRepository.createdWith)
    }

    @Test
    fun `errors stay hidden until the first attempt`() = runTest {
        // Краснеть на ещё не заполненной форме — значит ругаться авансом.
        seed()

        assertTrue(viewModel().state.value.visibleErrors.isEmpty())
    }

    @Test
    fun `a filled form creates the order`() = runTest {
        seed()
        val viewModel = viewModel()
        viewModel.onEvent(CheckoutEvent.AddressChanged("Amir Temur 1"))

        viewModel.onEvent(CheckoutEvent.SubmitClicked)

        val (cart, form) = orderRepository.createdWith!!
        assertEquals(PLACE_ID, cart.placeId)
        assertEquals("Amir Temur 1", form.address)
        assertFalse(viewModel.state.value.isSubmitting)
    }

    @Test
    fun `a failed cash order keeps the form and shows the error`() = runTest {
        seed()
        orderRepository.created = ApiResult.Failure(ApiError.NoConnection)
        val viewModel = viewModel()
        viewModel.onEvent(CheckoutEvent.AddressChanged("Amir Temur 1"))
        viewModel.onEvent(CheckoutEvent.PaymentSelected(PaymentMethod.Cash))

        viewModel.onEvent(CheckoutEvent.SubmitClicked)

        assertEquals(ApiError.NoConnection, viewModel.state.value.submitError?.error)
        assertEquals("Amir Temur 1", viewModel.state.value.form.address)
        assertFalse(viewModel.state.value.isSubmitting)
    }

    /**
     * У оплаты кошельком отказ остаётся в шторке подтверждения (8.3), а не
     * уходит под форму: там же кнопка «повторить» — тем же ключом
     * идемпотентности, — и там же названа сумма, из-за которой всё началось.
     */
    @Test
    fun `a failed wallet payment keeps the form and shows the refusal in the sheet`() = runTest {
        seed()
        orderRepository.created = ApiResult.Failure(ApiError.NoConnection)
        val viewModel = viewModel()
        viewModel.onEvent(CheckoutEvent.AddressChanged("Amir Temur 1"))

        viewModel.onEvent(CheckoutEvent.SubmitClicked)

        val rejection = viewModel.state.value.payment?.rejection
        assertEquals(
            ApiError.NoConnection,
            (rejection as WalletPaymentRejection.Declined).failure.error,
        )
        assertTrue(viewModel.state.value.payment?.canRetry == true)
        assertEquals("Amir Temur 1", viewModel.state.value.form.address)
    }

    @Test
    fun `wallet payment reports how much is missing`() = runTest {
        seed()
        walletRepository.wallet = ApiResult.Success(Wallet(availableSum = 50_000))
        val viewModel = viewModel()
        viewModel.onEvent(CheckoutEvent.AddressChanged("Amir Temur 1"))

        viewModel.onEvent(CheckoutEvent.SubmitClicked)

        assertEquals(10_000L, viewModel.state.value.insufficientFunds?.missingSum)
        assertNull(orderRepository.createdWith)
    }

    @Test
    fun `switching to cash unblocks an order the wallet cannot pay`() = runTest {
        seed()
        walletRepository.wallet = ApiResult.Success(Wallet(availableSum = 0))
        val viewModel = viewModel()
        viewModel.onEvent(CheckoutEvent.AddressChanged("Amir Temur 1"))

        viewModel.onEvent(CheckoutEvent.PaymentSelected(PaymentMethod.Cash))

        assertTrue(viewModel.state.value.canSubmit)
    }

    @Test
    fun `an unknown balance does not block the order`() = runTest {
        // Решающее слово всё равно за сервером; отказать из-за неотвеченного
        // запроса — хуже.
        seed()
        walletRepository.wallet = ApiResult.Failure(ApiError.Timeout)
        val viewModel = viewModel()
        viewModel.onEvent(CheckoutEvent.AddressChanged("Amir Temur 1"))

        assertFalse(viewModel.state.value.balanceKnown)
        assertTrue(viewModel.state.value.canSubmit)
    }

    @Test
    fun `an empty cart cannot be submitted`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(CheckoutEvent.SubmitClicked)

        assertTrue(viewModel.state.value.errors.contains(CheckoutError.EmptyCart))
        assertNull(orderRepository.createdWith)
    }

    @Test
    fun `the created order id reaches the screen`() = runTest {
        seed()
        orderRepository.created = ApiResult.Success("o-42")
        val viewModel = viewModel()
        viewModel.onEvent(CheckoutEvent.AddressChanged("Amir Temur 1"))

        viewModel.onEvent(CheckoutEvent.SubmitClicked)

        assertEquals(CheckoutEffect.OrderCreated("o-42"), viewModel.effects.first())
    }

    // --- Оплата кошельком: подтверждение и идемпотентность (8.3, issue #12) ---

    @Test
    fun `wallet payment waits for the pin before creating the order`() = runTest {
        confirmationPolicy.method = PaymentConfirmationMethod.Pin
        seed()
        val viewModel = viewModel()
        viewModel.onEvent(CheckoutEvent.AddressChanged("Amir Temur 1"))

        viewModel.onEvent(CheckoutEvent.SubmitClicked)

        assertNotNull(viewModel.state.value.payment)
        assertEquals(0, orderRepository.createCount)

        viewModel.onEvent(CheckoutEvent.PaymentPinChanged(PIN))

        assertEquals(1, orderRepository.createCount)
        assertEquals(CheckoutEffect.OrderCreated("o-1"), viewModel.effects.first())
    }

    @Test
    fun `a second tap on submit does not create a second order`() = runTest {
        // До 8.3 второе нажатие ловил только флаг isSubmitting — то есть не
        // ловил ничего после отказа.
        confirmationPolicy.method = PaymentConfirmationMethod.Pin
        seed()
        val viewModel = viewModel()
        viewModel.onEvent(CheckoutEvent.AddressChanged("Amir Temur 1"))

        viewModel.onEvent(CheckoutEvent.SubmitClicked)
        viewModel.onEvent(CheckoutEvent.SubmitClicked)
        viewModel.onEvent(CheckoutEvent.PaymentPinChanged(PIN))

        assertEquals(1, orderRepository.createCount)
    }

    @Test
    fun `a wrong pin leaves the order uncreated`() = runTest {
        confirmationPolicy.method = PaymentConfirmationMethod.Pin
        seed()
        val viewModel = viewModel()
        viewModel.onEvent(CheckoutEvent.AddressChanged("Amir Temur 1"))
        viewModel.onEvent(CheckoutEvent.SubmitClicked)

        repeat(3) { viewModel.onEvent(CheckoutEvent.PaymentPinChanged("000000")) }

        assertEquals(
            WalletPaymentRejection.ConfirmationFailed,
            viewModel.state.value.payment?.rejection,
        )
        assertEquals(0, orderRepository.createCount)
    }

    @Test
    fun `topping up from the sheet closes it and opens the wallet`() = runTest {
        confirmationPolicy.method = PaymentConfirmationMethod.Pin
        walletRepository.wallet = ApiResult.Success(Wallet(availableSum = 0))
        seed()
        val viewModel = viewModel()
        viewModel.onEvent(CheckoutEvent.AddressChanged("Amir Temur 1"))
        viewModel.onEvent(CheckoutEvent.PaymentSelected(PaymentMethod.Cash))
        viewModel.onEvent(CheckoutEvent.PaymentSelected(PaymentMethod.Wallet))

        viewModel.onEvent(CheckoutEvent.TopUpClicked)

        assertNull(viewModel.state.value.payment)
        assertEquals(CheckoutEffect.OpenWallet, viewModel.effects.first())
    }

    @Test
    fun `a cash order repeated after a refusal keeps the same idempotency key`() = runTest {
        // Повтор — тот же заказ, а не второй: наличные подтверждения не
        // требуют, но от двойного оформления защищены так же.
        seed()
        orderRepository.created = ApiResult.Failure(ApiError.NoConnection)
        val viewModel = viewModel()
        viewModel.onEvent(CheckoutEvent.AddressChanged("Amir Temur 1"))
        viewModel.onEvent(CheckoutEvent.PaymentSelected(PaymentMethod.Cash))

        viewModel.onEvent(CheckoutEvent.SubmitClicked)
        viewModel.onEvent(CheckoutEvent.SubmitClicked)

        assertEquals(2, orderRepository.createKeys.size)
        assertEquals(orderRepository.createKeys[0], orderRepository.createKeys[1])
    }

    private fun seed() {
        cartRepository.seed(
            Cart(
                placeId = PLACE_ID,
                placeName = "Osh markazi",
                lines = listOf(cartLine("osh", unitPriceSum = 30_000, quantity = 2)),
            ),
        )
    }

    private fun viewModel() = CheckoutViewModel(
        cartRepository = cartRepository,
        orderRepository = orderRepository,
        walletRepository = walletRepository,
        roleRepository = roleRepository,
        paymentFlows = WalletPaymentFlowFactory(
            walletRepository = walletRepository,
            pinStorage = pinStorage,
            confirmationPolicy = confirmationPolicy,
        ),
        savedStateHandle = SavedStateHandle(mapOf("placeId" to PLACE_ID)),
    )

    private companion object {
        const val PLACE_ID = "place-1"
        const val PIN = "123456"
    }
}
