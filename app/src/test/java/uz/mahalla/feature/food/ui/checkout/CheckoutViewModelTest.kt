package uz.mahalla.feature.food.ui.checkout

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.food.domain.Cart
import uz.mahalla.feature.food.domain.CheckoutError
import uz.mahalla.feature.food.domain.DeliveryMethod
import uz.mahalla.feature.food.domain.PaymentMethod
import uz.mahalla.feature.food.domain.PromoCode
import uz.mahalla.feature.food.domain.PromoKind
import uz.mahalla.testutil.FakeCartRepository
import uz.mahalla.testutil.FakeOrderRepository
import uz.mahalla.testutil.FakeWalletRepository
import uz.mahalla.testutil.MainDispatcherRule
import uz.mahalla.testutil.cartLine
import uz.mahalla.testutil.order
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Оформление заказа (эпик 5.3). Часы фиксированы: «слишком рано» иначе
 * проверялось бы только в определённое время суток.
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

    @Test
    fun `delivery adds the fee of the place, pickup does not`() = runTest {
        seed()
        val viewModel = viewModel()

        assertEquals(15_000L, viewModel.state.value.totals.deliverySum)

        viewModel.onEvent(CheckoutEvent.MethodSelected(DeliveryMethod.Pickup))

        assertEquals(0L, viewModel.state.value.totals.deliverySum)
        assertEquals(60_000L, viewModel.state.value.totals.totalSum)
    }

    @Test
    fun `the promo of the cart is part of the total`() = runTest {
        seed()
        cartRepository.applyPromo(PromoCode("TEN", PromoKind.Percent, value = 10))

        val totals = viewModel().state.value.totals

        assertEquals(6_000L, totals.discountSum)
        assertEquals(69_000L, totals.totalSum)
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

        assertEquals(ApiError.NoConnection, viewModel.state.value.submitError)
        assertEquals("Amir Temur 1", viewModel.state.value.form.address)
        assertFalse(viewModel.state.value.isSubmitting)
    }

    @Test
    fun `wallet payment reports how much is missing`() = runTest {
        seed()
        walletRepository.balance = ApiResult.Success(50_000)
        val viewModel = viewModel()
        viewModel.onEvent(CheckoutEvent.AddressChanged("Amir Temur 1"))

        viewModel.onEvent(CheckoutEvent.SubmitClicked)

        assertEquals(25_000L, viewModel.state.value.insufficientFunds?.missingSum)
        assertNull(orderRepository.createdWith)
    }

    @Test
    fun `switching to cash unblocks an order the wallet cannot pay`() = runTest {
        seed()
        walletRepository.balance = ApiResult.Success(0)
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
        walletRepository.balance = ApiResult.Failure(ApiError.Timeout)
        val viewModel = viewModel()
        viewModel.onEvent(CheckoutEvent.AddressChanged("Amir Temur 1"))

        assertFalse(viewModel.state.value.balanceKnown)
        assertTrue(viewModel.state.value.canSubmit)
    }

    @Test
    fun `a scheduled order needs a time that the kitchen can make`() = runTest {
        seed()
        val viewModel = viewModel()
        viewModel.onEvent(CheckoutEvent.MethodSelected(DeliveryMethod.Pickup))

        viewModel.onEvent(CheckoutEvent.AsapToggled(false))

        assertTrue(viewModel.state.value.errors.contains(CheckoutError.TimeRequired))

        viewModel.onEvent(CheckoutEvent.SlotSelected(viewModel.state.value.slots.first()))

        assertTrue(viewModel.state.value.errors.isEmpty())
    }

    @Test
    fun `switching back to asap drops the chosen slot`() = runTest {
        seed()
        val viewModel = viewModel()
        viewModel.onEvent(CheckoutEvent.MethodSelected(DeliveryMethod.Pickup))
        viewModel.onEvent(CheckoutEvent.SlotSelected(viewModel.state.value.slots.first()))

        viewModel.onEvent(CheckoutEvent.AsapToggled(true))

        assertTrue(viewModel.state.value.form.asap)
        assertNull(viewModel.state.value.form.scheduledAt)
    }

    @Test
    fun `a slot the kitchen can no longer make leaves the list together with the selection`() = runTest {
        // Список слотов считался один раз при открытии экрана, а валидатор
        // сравнивал выбор с живым «сейчас»: через минуту первый слот
        // отвергался как «слишком рано», оставаясь при этом в списке.
        seed()
        val clock = MovableClock(Instant.parse("2026-08-26T07:00:00Z"))
        val viewModel = viewModel(clock)
        viewModel.onEvent(CheckoutEvent.MethodSelected(DeliveryMethod.Pickup))
        val slot = viewModel.state.value.slots.first()
        viewModel.onEvent(CheckoutEvent.SlotSelected(slot))
        assertTrue(viewModel.state.value.errors.isEmpty())

        clock.advanceMinutes(2)
        viewModel.onEvent(CheckoutEvent.SubmitClicked)

        val state = viewModel.state.value
        assertFalse(state.slots.contains(slot))
        assertNull(state.form.scheduledAt)
        // Просьба выбрать время, а не «слишком рано» на слоте из списка.
        assertTrue(state.errors.contains(CheckoutError.TimeRequired))
        assertTrue(state.errors.none { it is CheckoutError.TimeTooSoon })
        assertNull(orderRepository.createdWith)
    }

    @Test
    fun `every offered slot passes validation after the form is filled`() = runTest {
        seed()
        val clock = MovableClock(Instant.parse("2026-08-26T07:00:00Z"))
        val viewModel = viewModel(clock)
        viewModel.onEvent(CheckoutEvent.MethodSelected(DeliveryMethod.Pickup))

        clock.advanceMinutes(3)
        viewModel.onEvent(CheckoutEvent.AsapToggled(false))
        viewModel.onEvent(CheckoutEvent.SlotSelected(viewModel.state.value.slots.first()))

        assertTrue(viewModel.state.value.errors.isEmpty())
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
        orderRepository.created = ApiResult.Success(order(id = "o-42"))
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
                deliverySum = 15_000,
                lines = listOf(cartLine("osh", unitPriceSum = 30_000, quantity = 2)),
            ),
        )
    }

    private fun viewModel(
        clock: Clock = Clock.fixed(Instant.parse("2026-08-26T07:00:00Z"), ZoneOffset.UTC),
    ) = CheckoutViewModel(
        cartRepository = cartRepository,
        orderRepository = orderRepository,
        walletRepository = walletRepository,
        clock = clock,
        savedStateHandle = SavedStateHandle(mapOf("placeId" to PLACE_ID)),
    )

    /** Часы, которые можно подвинуть: время между открытием экрана и сабмитом. */
    private class MovableClock(
        private var instant: Instant,
        private val zone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {

        fun advanceMinutes(minutes: Long) {
            instant = instant.plusSeconds(minutes * 60)
        }

        override fun instant(): Instant = instant

        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MovableClockView(this, zone)
    }

    /**
     * `withZone` обязан вернуть те же часы в другой зоне: ViewModel читает время
     * через `clock.withZone(AppZone)`, и копия остановилась бы на прежнем
     * значении.
     */
    private class MovableClockView(private val source: Clock, private val zone: ZoneId) : Clock() {
        override fun instant(): Instant = source.instant()
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = MovableClockView(source, zone)
    }

    private companion object {
        const val PLACE_ID = "place-1"
    }
}
