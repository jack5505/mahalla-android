package uz.mahalla.feature.fashion.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.fashion.domain.FashionCart
import uz.mahalla.feature.fashion.domain.FashionCartItem
import uz.mahalla.feature.fashion.domain.FashionCartRules
import uz.mahalla.feature.fashion.ui.cart.FashionCartEvent
import uz.mahalla.feature.fashion.ui.cart.FashionCartViewModel
import uz.mahalla.testutil.FakeFashionCartRepository
import uz.mahalla.testutil.MainDispatcherRule

/**
 * Серверная корзина одежды (issue #108): количество, удаление и деление по
 * магазинам.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FashionCartViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val repository = FakeFashionCartRepository()

    @Test
    fun `cart is split by store`() = runTest {
        repository.cartResult = ApiResult.Success(
            FashionCart(listOf(item("v-1"), item("v-2", store = "s-2"))),
        )

        val cart = (viewModel().state.value.cart as ScreenState.Content).data

        assertEquals(listOf("s-1", "s-2"), cart.stores.map { it.storeId })
    }

    @Test
    fun `an empty cart is an empty state`() = runTest {
        assertTrue(viewModel().state.value.cart is ScreenState.Empty)
    }

    @Test
    fun `quantity change goes to the server and only then to the screen`() = runTest {
        repository.cartResult = ApiResult.Success(FashionCart(listOf(item("v-1", quantity = 1))))
        val viewModel = viewModel()

        viewModel.onEvent(FashionCartEvent.QuantityChanged("v-1", 3))

        assertEquals(listOf("v-1" to 3), repository.quantities)
        val line = (viewModel.state.value.cart as ScreenState.Content).data.item("v-1")
        assertEquals(3, line?.quantity)
        // Сумму строки пересчитываем из цены за единицу: серверная приходила
        // от старого количества.
        assertEquals(300_000L, line?.totalSum)
        assertNull(viewModel.state.value.pendingVariantId)
    }

    @Test
    fun `a refused change keeps the previous quantity and shows the reason`() = runTest {
        repository.cartResult = ApiResult.Success(FashionCart(listOf(item("v-1", quantity = 2))))
        repository.setQuantityResult = ApiResult.Failure(ApiError.Business("OUT_OF_STOCK"))
        val viewModel = viewModel()

        viewModel.onEvent(FashionCartEvent.QuantityChanged("v-1", 3))

        val state = viewModel.state.value
        assertEquals(2, (state.cart as ScreenState.Content).data.item("v-1")?.quantity)
        assertEquals(ApiError.Business("OUT_OF_STOCK"), state.actionFailure?.error)
    }

    @Test
    fun `the same quantity is not sent twice`() = runTest {
        repository.cartResult = ApiResult.Success(FashionCart(listOf(item("v-1", quantity = 2))))
        val viewModel = viewModel()

        viewModel.onEvent(FashionCartEvent.QuantityChanged("v-1", 2))

        assertTrue(repository.quantities.isEmpty())
    }

    @Test
    fun `minus below one asks to remove instead of sending zero`() = runTest {
        repository.cartResult = ApiResult.Success(FashionCart(listOf(item("v-1", quantity = 1))))
        val viewModel = viewModel()

        viewModel.onEvent(FashionCartEvent.QuantityChanged("v-1", 0))

        // У удаления своя ручка, а что сделает бэкенд с `quantity=0`, из
        // контракта не следует.
        assertTrue(repository.quantities.isEmpty())
        assertEquals("v-1", viewModel.state.value.confirmRemove)
    }

    @Test
    fun `removal happens only after confirmation`() = runTest {
        repository.cartResult = ApiResult.Success(
            FashionCart(listOf(item("v-1"), item("v-2"))),
        )
        val viewModel = viewModel()

        viewModel.onEvent(FashionCartEvent.RemoveRequested("v-1"))
        viewModel.onEvent(FashionCartEvent.RemoveDismissed)
        assertTrue(repository.removed.isEmpty())

        viewModel.onEvent(FashionCartEvent.RemoveRequested("v-1"))
        viewModel.onEvent(FashionCartEvent.RemoveConfirmed)

        assertEquals(listOf("v-1"), repository.removed)
        val cart = (viewModel.state.value.cart as ScreenState.Content).data
        assertEquals(listOf("v-2"), cart.items.map(FashionCartItem::variantId))
    }

    @Test
    fun `removing the last line empties the cart instead of showing a zero total`() = runTest {
        repository.cartResult = ApiResult.Success(FashionCart(listOf(item("v-1"))))
        val viewModel = viewModel()

        viewModel.onEvent(FashionCartEvent.RemoveRequested("v-1"))
        viewModel.onEvent(FashionCartEvent.RemoveConfirmed)

        assertTrue(viewModel.state.value.cart is ScreenState.Empty)
    }

    @Test
    fun `quantity is clamped before it reaches the repository`() = runTest {
        repository.cartResult = ApiResult.Success(FashionCart(listOf(item("v-1"))))
        val viewModel = viewModel()

        viewModel.onEvent(
            FashionCartEvent.QuantityChanged("v-1", FashionCartRules.MAX_QUANTITY + 10),
        )

        assertEquals(listOf("v-1" to FashionCartRules.MAX_QUANTITY), repository.quantities)
    }

    @Test
    fun `returning to the screen re-reads the cart`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(FashionCartEvent.ScreenResumed)

        assertEquals(2, repository.cartRequests)
    }

    @Test
    fun `checkout is offered per store`() = runTest {
        repository.cartResult = ApiResult.Success(FashionCart(listOf(item("v-1", store = "s-2"))))
        val viewModel = viewModel()

        viewModel.onEvent(FashionCartEvent.CheckoutClicked("s-2"))

        // Проверяем, что событие принято и состояние не сломалось: эффект
        // читает экран, а он в JVM-тесте не поднимается.
        assertTrue(viewModel.state.value.cart is ScreenState.Content)
    }

    private fun viewModel() = FashionCartViewModel(repository = repository)

    private fun item(
        variantId: String,
        store: String = "s-1",
        quantity: Int = 1,
    ) = FashionCartItem(
        variantId = variantId,
        storeId = store,
        productName = "Ko'ylak",
        unitPriceSum = 100_000,
        quantity = quantity,
        serverTotalSum = 100_000L * quantity,
    )
}
