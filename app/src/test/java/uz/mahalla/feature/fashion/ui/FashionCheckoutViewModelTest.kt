package uz.mahalla.feature.fashion.ui

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.fashion.domain.FashionCart
import uz.mahalla.feature.fashion.domain.FashionCartItem
import uz.mahalla.feature.fashion.ui.checkout.FashionCheckoutEvent
import uz.mahalla.feature.fashion.ui.checkout.FashionCheckoutViewModel
import uz.mahalla.feature.food.domain.CheckoutError
import uz.mahalla.feature.food.domain.DeliveryMethod
import uz.mahalla.feature.food.domain.PaymentMethod
import uz.mahalla.feature.role.data.RoleProfile
import uz.mahalla.feature.role.domain.CustomerForm
import uz.mahalla.feature.wallet.domain.Wallet
import uz.mahalla.navigation.FashionArgs
import uz.mahalla.testutil.FakeFashionCartRepository
import uz.mahalla.testutil.FakeFashionOrderRepository
import uz.mahalla.testutil.FakeRoleRepository
import uz.mahalla.testutil.FakeWalletRepository
import uz.mahalla.testutil.MainDispatcherRule

/**
 * Оформление заказа одежды (issue #108): состав одного магазина, форма,
 * баланс и отправка.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FashionCheckoutViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val cartRepository = FakeFashionCartRepository()
    private val orderRepository = FakeFashionOrderRepository()
    private val walletRepository = FakeWalletRepository()
    private val roleRepository = FakeRoleRepository()

    @Test
    fun `only the lines of this store are ordered`() = runTest {
        cartRepository.cartResult = ApiResult.Success(
            FashionCart(
                listOf(
                    item("v-1", quantity = 2),
                    item("v-2", store = "s-2"),
                    item("v-3"),
                ),
            ),
        )

        val state = viewModel().state.value

        assertEquals(
            listOf("v-1", "v-3"),
            state.items.map(FashionCartItem::variantId),
        )
        // Доставка в итог не входит: её называет сервер уже в ответе о заказе.
        assertEquals(450_000L, state.totals.totalSum)
    }

    @Test
    fun `address is prefilled from the customer form but never overwrites typing`() = runTest {
        val viewModel = FashionCheckoutViewModel(
            cartRepository = cartRepository,
            orderRepository = orderRepository,
            walletRepository = walletRepository,
            roleRepository = FakeRoleRepository(
                RoleProfile(customer = CustomerForm(address = "Amir Temur 1")),
            ),
            savedStateHandle = handle(),
        )

        assertEquals("Amir Temur 1", viewModel.state.value.form.address)
    }

    @Test
    fun `submitting an unfinished form shows the reasons instead of sending it`() = runTest {
        cartRepository.cartResult = ApiResult.Success(FashionCart(listOf(item("v-1"))))
        val viewModel = viewModel()

        // Краснеть авансом на пустом адресе не за что.
        assertTrue(viewModel.state.value.visibleErrors.isEmpty())

        viewModel.onEvent(FashionCheckoutEvent.SubmitClicked)

        assertTrue(orderRepository.created.isEmpty())
        assertTrue(
            viewModel.state.value.visibleErrors.any { it is CheckoutError.AddressRequired },
        )
    }

    @Test
    fun `a finished form goes to the repository with the store and the lines`() = runTest {
        cartRepository.cartResult = ApiResult.Success(
            FashionCart(listOf(item("v-1", quantity = 2))),
        )
        val viewModel = viewModel()
        viewModel.onEvent(FashionCheckoutEvent.AddressChanged("Amir Temur 1"))

        viewModel.onEvent(FashionCheckoutEvent.SubmitClicked)

        val created = orderRepository.created.single()
        assertEquals(STORE, created.storeId)
        assertEquals(listOf("v-1" to 2), created.items)
        assertEquals(DeliveryMethod.Delivery, created.form.method)
        assertEquals(PaymentMethod.Wallet, created.form.payment)
        // Экран не уходит сам: молчаливый переход читается как «ничего не
        // произошло» (issue #49).
        assertTrue(viewModel.state.value.orderCreated)
    }

    @Test
    fun `a second tap does not create a second order`() = runTest {
        cartRepository.cartResult = ApiResult.Success(FashionCart(listOf(item("v-1"))))
        val viewModel = viewModel()
        viewModel.onEvent(FashionCheckoutEvent.MethodSelected(DeliveryMethod.Pickup))

        viewModel.onEvent(FashionCheckoutEvent.SubmitClicked)
        viewModel.onEvent(FashionCheckoutEvent.SubmitClicked)

        assertEquals(1, orderRepository.created.size)
    }

    @Test
    fun `a refused order keeps the form and shows the server text`() = runTest {
        cartRepository.cartResult = ApiResult.Success(FashionCart(listOf(item("v-1"))))
        orderRepository.createResult = ApiResult.Failure(ApiError.Business("OUT_OF_STOCK"))
        val viewModel = viewModel()
        viewModel.onEvent(FashionCheckoutEvent.MethodSelected(DeliveryMethod.Pickup))

        viewModel.onEvent(FashionCheckoutEvent.SubmitClicked)

        val state = viewModel.state.value
        assertFalse(state.orderCreated)
        assertEquals(ApiError.Business("OUT_OF_STOCK"), state.submitError?.error)
        assertEquals(1, state.items.size)
    }

    @Test
    fun `wallet without enough money blocks the order and says how much is missing`() = runTest {
        cartRepository.cartResult = ApiResult.Success(
            FashionCart(listOf(item("v-1", quantity = 2))),
        )
        walletRepository.wallet = ApiResult.Success(
            Wallet(balanceSum = 100_000, availableSum = 100_000),
        )
        val viewModel = viewModel()
        viewModel.onEvent(FashionCheckoutEvent.MethodSelected(DeliveryMethod.Pickup))

        viewModel.onEvent(FashionCheckoutEvent.SubmitClicked)

        assertEquals(200_000L, viewModel.state.value.insufficientFunds?.missingSum)
        assertTrue(orderRepository.created.isEmpty())
    }

    @Test
    fun `an unknown balance does not block the order`() = runTest {
        // Отказать в оформлении из-за неотвеченного запроса хуже, чем
        // получить отказ на сервере, который всё равно проверит деньги.
        cartRepository.cartResult = ApiResult.Success(FashionCart(listOf(item("v-1"))))
        walletRepository.wallet = ApiResult.Failure(ApiError.Timeout)
        val viewModel = viewModel()
        viewModel.onEvent(FashionCheckoutEvent.MethodSelected(DeliveryMethod.Pickup))

        viewModel.onEvent(FashionCheckoutEvent.SubmitClicked)

        assertFalse(viewModel.state.value.balanceKnown)
        assertEquals(1, orderRepository.created.size)
    }

    @Test
    fun `an empty store cart offers nothing to submit`() = runTest {
        // Корзину могли забрать в заказ на другом устройстве, пока человек
        // шёл сюда.
        cartRepository.cartResult = ApiResult.Success(FashionCart(listOf(item("v-1", "s-2"))))
        val viewModel = viewModel()

        assertTrue(viewModel.state.value.isEmpty)
        viewModel.onEvent(FashionCheckoutEvent.SubmitClicked)
        assertTrue(orderRepository.created.isEmpty())
    }

    @Test
    fun `a failed cart read offers a retry`() = runTest {
        cartRepository.cartResult = ApiResult.Failure(ApiError.Unauthorized)
        val viewModel = viewModel()
        assertEquals(ApiError.Unauthorized, viewModel.state.value.loadFailure?.error)

        cartRepository.cartResult = ApiResult.Success(FashionCart(listOf(item("v-1"))))
        viewModel.onEvent(FashionCheckoutEvent.Retry)

        assertEquals(1, viewModel.state.value.items.size)
    }

    private fun viewModel() = FashionCheckoutViewModel(
        cartRepository = cartRepository,
        orderRepository = orderRepository,
        walletRepository = walletRepository,
        roleRepository = roleRepository,
        savedStateHandle = handle(),
    )

    private fun handle() = SavedStateHandle(mapOf(FashionArgs.STORE_ID to STORE))

    private fun item(
        variantId: String,
        store: String = STORE,
        quantity: Int = 1,
    ) = FashionCartItem(
        variantId = variantId,
        storeId = store,
        productName = "Ko'ylak",
        unitPriceSum = 150_000,
        quantity = quantity,
    )

    private companion object {
        const val STORE = "s-1"
    }
}
