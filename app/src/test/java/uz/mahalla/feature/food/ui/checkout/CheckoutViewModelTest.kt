package uz.mahalla.feature.food.ui.checkout

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.core.analytics.AnalyticsEvents
import uz.mahalla.core.analytics.AnalyticsVertical
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.food.domain.Cart
import uz.mahalla.feature.food.domain.CheckoutError
import uz.mahalla.feature.food.domain.DeliveryMethod
import uz.mahalla.feature.food.domain.PaymentMethod
import uz.mahalla.feature.onboarding.domain.City
import uz.mahalla.feature.role.domain.CustomerForm
import uz.mahalla.testutil.FakeAnalyticsTracker
import uz.mahalla.testutil.FakeCartRepository
import uz.mahalla.testutil.FakeDeliveryFeeRepository
import uz.mahalla.testutil.FakeOrderRepository
import uz.mahalla.testutil.FakeRoleRepository
import uz.mahalla.feature.wallet.domain.Wallet
import uz.mahalla.testutil.FakeWalletRepository
import uz.mahalla.testutil.MainDispatcherRule
import uz.mahalla.testutil.cartLine

/**
 * Оформление заказа (эпик 5.3).
 *
 * Ни времени заказа, ни комментария в форме нет: `PlaceOrderRequest` бэкенда
 * их не принимает.
 *
 * Стоимость доставки приезжает отдельным запросом с задержкой (issue #179):
 * тесты, которым она важна, двигают время `advanceUntilIdle()`.
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
    private val deliveryFeeRepository = FakeDeliveryFeeRepository()

    @Test
    fun `an unknown delivery fee leaves the total at the price of the items`() = runTest {
        // Сервер доставку не назвал: итог — сумма позиций, как до issue #179,
        // а не выдуманное число.
        seed()
        deliveryFeeRepository.fee = ApiResult.Success(null)
        val viewModel = viewModel()

        advanceUntilIdle()

        assertEquals(0L, viewModel.state.value.totals.deliverySum)
        assertEquals(60_000L, viewModel.state.value.totals.totalSum)
    }

    @Test
    fun `the delivery fee from the server is part of the total`() = runTest {
        // Именно эту сумму человек и увидит списанной (issue #179).
        seed()
        deliveryFeeRepository.fee = ApiResult.Success(100)
        val viewModel = viewModel()

        advanceUntilIdle()

        assertEquals(listOf(60_000L), deliveryFeeRepository.requestedSums)
        assertEquals(100L, viewModel.state.value.totals.deliverySum)
        assertEquals(60_100L, viewModel.state.value.totals.totalSum)
    }

    @Test
    fun `pickup has no delivery fee, neither in the total nor in a request`() = runTest {
        // У `PICKUP` и `DINE_IN` доставки в заказе нет — спрашивать цену
        // того, чего не будет, незачем.
        seed()
        deliveryFeeRepository.fee = ApiResult.Success(100)
        val viewModel = viewModel()
        advanceUntilIdle()
        deliveryFeeRepository.requestedSums.clear()

        viewModel.onEvent(CheckoutEvent.MethodSelected(DeliveryMethod.Pickup))
        advanceUntilIdle()

        assertEquals(0L, viewModel.state.value.totals.deliverySum)
        assertEquals(60_000L, viewModel.state.value.totals.totalSum)
        assertEquals(emptyList<Long>(), deliveryFeeRepository.requestedSums)
    }

    @Test
    fun `coming back to delivery asks for the fee again`() = runTest {
        // Иначе после «самовывоза» человек оформил бы доставку с итогом без
        // неё.
        seed()
        deliveryFeeRepository.fee = ApiResult.Success(100)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onEvent(CheckoutEvent.MethodSelected(DeliveryMethod.Pickup))
        advanceUntilIdle()

        viewModel.onEvent(CheckoutEvent.MethodSelected(DeliveryMethod.Delivery))
        advanceUntilIdle()

        assertEquals(60_100L, viewModel.state.value.totals.totalSum)
    }

    @Test
    fun `the wallet is checked against the total with the delivery fee`() = runTest {
        // Баланс, которого хватает на позиции, но не на доставку, — отказ
        // сервера при оформлении; сказать об этом надо раньше.
        seed()
        deliveryFeeRepository.fee = ApiResult.Success(5_000)
        walletRepository.wallet = ApiResult.Success(Wallet(availableSum = 60_000))
        val viewModel = viewModel()
        viewModel.onEvent(CheckoutEvent.AddressChanged("Amir Temur 1"))

        advanceUntilIdle()

        assertEquals(5_000L, viewModel.state.value.insufficientFunds?.missingSum)
    }

    @Test
    fun `a refused fee request does not block the order`() = runTest {
        seed()
        deliveryFeeRepository.fee = ApiResult.Failure(ApiError.NoConnection)
        val viewModel = viewModel()
        viewModel.onEvent(CheckoutEvent.AddressChanged("Amir Temur 1"))

        advanceUntilIdle()

        assertEquals(60_000L, viewModel.state.value.totals.totalSum)
        assertTrue(viewModel.state.value.canSubmit)
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
    fun `a failed order keeps the form and shows the error`() = runTest {
        seed()
        orderRepository.created = ApiResult.Failure(ApiError.NoConnection)
        val viewModel = viewModel()
        viewModel.onEvent(CheckoutEvent.AddressChanged("Amir Temur 1"))

        viewModel.onEvent(CheckoutEvent.SubmitClicked)

        assertEquals(ApiError.NoConnection, viewModel.state.value.submitError?.error)
        assertEquals("Amir Temur 1", viewModel.state.value.form.address)
        assertFalse(viewModel.state.value.isSubmitting)
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

    private fun seed() {
        cartRepository.seed(
            Cart(
                placeId = PLACE_ID,
                placeName = "Osh markazi",
                lines = listOf(cartLine("osh", unitPriceSum = 30_000, quantity = 2)),
            ),
        )
    }

    @Test
    fun `a created order is an ORDER of the food vertical`() = runTest {
        seed()
        orderRepository.created = ApiResult.Success("o-42")
        val viewModel = viewModel()
        viewModel.onEvent(CheckoutEvent.AddressChanged("Amir Temur 1"))

        viewModel.onEvent(CheckoutEvent.SubmitClicked)

        assertEquals(
            listOf(AnalyticsEvents.ordered(PLACE_ID, AnalyticsVertical.Food)),
            analytics.events,
        )
    }

    @Test
    fun `a refused order is not counted`() = runTest {
        seed()
        orderRepository.created = ApiResult.Failure(ApiError.Business("PLACE_CLOSED"))
        val viewModel = viewModel()
        viewModel.onEvent(CheckoutEvent.AddressChanged("Amir Temur 1"))

        viewModel.onEvent(CheckoutEvent.SubmitClicked)

        // Иначе воронка покажет заказы, которых не было.
        assertEquals(emptyList<Any>(), analytics.events)
    }

    /** Аналитика (issue #169): проверяем, что событие ушло и один раз. */
    private val analytics = FakeAnalyticsTracker()

    private fun viewModel() = CheckoutViewModel(
        cartRepository = cartRepository,
        orderRepository = orderRepository,
        walletRepository = walletRepository,
        roleRepository = roleRepository,
        deliveryFeeRepository = deliveryFeeRepository,
        analytics = analytics,
        savedStateHandle = SavedStateHandle(mapOf("placeId" to PLACE_ID)),
    )

    private companion object {
        const val PLACE_ID = "place-1"
    }
}
