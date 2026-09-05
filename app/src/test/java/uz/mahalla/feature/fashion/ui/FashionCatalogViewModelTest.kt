package uz.mahalla.feature.fashion.ui

import androidx.lifecycle.SavedStateHandle
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
import uz.mahalla.feature.fashion.domain.FashionCatalogPage
import uz.mahalla.feature.fashion.domain.FashionCategory
import uz.mahalla.feature.fashion.domain.FashionProduct
import uz.mahalla.feature.fashion.ui.catalog.FashionCatalogEvent
import uz.mahalla.feature.fashion.ui.catalog.FashionCatalogViewModel
import uz.mahalla.navigation.FashionArgs
import uz.mahalla.testutil.FakeFashionCartRepository
import uz.mahalla.testutil.FakeFashionRepository
import uz.mahalla.testutil.MainDispatcherRule

/**
 * Витрина магазина одежды (issue #108): категории, фильтр, догрузка и бейдж
 * корзины.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FashionCatalogViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val repository = FakeFashionRepository()
    private val cartRepository = FakeFashionCartRepository()

    @Test
    fun `catalog is requested for the store from the route`() = runTest {
        repository.categoriesResult = ApiResult.Success(listOf(category("c-1")))
        repository.defaultCatalog = ApiResult.Success(page(listOf(product("p-1"))))

        val state = viewModel().state.value

        assertEquals(STORE, repository.catalogRequests.single().storeId)
        assertNull(repository.catalogRequests.single().categoryId)
        assertEquals("Zara", state.placeName)
        assertEquals(
            listOf("p-1"),
            (state.products as ScreenState.Content).data.map(FashionProduct::id),
        )
    }

    @Test
    fun `empty catalog is an empty state, not an error`() = runTest {
        val state = viewModel().state.value

        assertTrue(state.products is ScreenState.Empty)
    }

    @Test
    fun `a broken category list does not hide the products`() = runTest {
        // Справочник общий на весь бэкенд: его отказ — не повод прятать
        // витрину, которая уже приехала.
        repository.categoriesResult = ApiResult.Failure(ApiError.NoConnection)
        repository.defaultCatalog = ApiResult.Success(page(listOf(product("p-1"))))

        val state = viewModel().state.value

        assertTrue(state.categories is ScreenState.Error)
        assertTrue(state.products is ScreenState.Content)
    }

    @Test
    fun `choosing a category reloads the catalog with it`() = runTest {
        repository.categoriesResult = ApiResult.Success(listOf(category("c-1")))
        val viewModel = viewModel()

        viewModel.onEvent(FashionCatalogEvent.CategorySelected("c-1"))

        assertEquals("c-1", viewModel.state.value.selectedCategoryId)
        assertEquals("c-1", repository.catalogRequests.last().categoryId)
    }

    @Test
    fun `tapping the selected category drops the filter`() = runTest {
        val viewModel = viewModel()
        viewModel.onEvent(FashionCatalogEvent.CategorySelected("c-1"))

        viewModel.onEvent(FashionCatalogEvent.CategorySelected("c-1"))

        assertNull(viewModel.state.value.selectedCategoryId)
        assertNull(repository.catalogRequests.last().categoryId)
        assertEquals(3, repository.catalogRequests.size)
    }

    @Test
    fun `load more appends the next page and deduplicates it`() = runTest {
        repository.catalogPages[0] = ApiResult.Success(
            page(listOf(product("p-1"), product("p-2")), totalPages = 2),
        )
        repository.catalogPages[1] = ApiResult.Success(
            // «p-2» приехал дважды: витрина изменилась между запросами. В
            // `LazyColumn` дубликат ключа — падение.
            page(listOf(product("p-2"), product("p-3")), page = 1, totalPages = 2),
        )
        val viewModel = viewModel()

        viewModel.onEvent(FashionCatalogEvent.LoadMore)

        val products = (viewModel.state.value.products as ScreenState.Content).data
        assertEquals(listOf("p-1", "p-2", "p-3"), products.map(FashionProduct::id))
        assertEquals(listOf(0, 1), repository.catalogRequests.map { it.page })
    }

    @Test
    fun `a failed load more keeps what is on screen and offers a retry`() = runTest {
        repository.catalogPages[0] = ApiResult.Success(
            page(listOf(product("p-1")), totalPages = 2),
        )
        repository.catalogPages[1] = ApiResult.Failure(ApiError.Timeout)
        val viewModel = viewModel()

        viewModel.onEvent(FashionCatalogEvent.LoadMore)

        val state = viewModel.state.value
        assertTrue(state.products is ScreenState.Content)
        assertEquals(ApiError.Timeout, state.loadMoreFailure?.error)
    }

    @Test
    fun `cart badge counts units and survives a failing cart`() = runTest {
        cartRepository.cartResult = ApiResult.Success(
            FashionCart(listOf(cartItem("v-1", quantity = 2), cartItem("v-2"))),
        )
        val viewModel = viewModel()
        assertEquals(3, viewModel.state.value.cartCount)

        // Отказ (в том числе `401` до входа) бейдж не трогает: обнулить его
        // из-за пропавшей сети значит соврать, что корзина пуста.
        cartRepository.cartResult = ApiResult.Failure(ApiError.Unauthorized)
        viewModel.onEvent(FashionCatalogEvent.ScreenResumed)

        assertEquals(3, viewModel.state.value.cartCount)
    }

    @Test
    fun `returning to the screen refreshes only the cart`() = runTest {
        val viewModel = viewModel()
        val catalogRequests = repository.catalogRequests.size

        viewModel.onEvent(FashionCatalogEvent.ScreenResumed)

        assertEquals(catalogRequests, repository.catalogRequests.size)
        assertEquals(2, cartRepository.cartRequests)
    }

    private fun viewModel() = FashionCatalogViewModel(
        repository = repository,
        cartRepository = cartRepository,
        savedStateHandle = SavedStateHandle(
            mapOf(FashionArgs.PLACE_ID to STORE, FashionArgs.PLACE_NAME to "Zara"),
        ),
    )

    private fun page(
        items: List<FashionProduct>,
        page: Int = 0,
        totalPages: Int? = null,
    ) = FashionCatalogPage(items = items, page = page, totalPages = totalPages)

    private fun product(id: String) = FashionProduct(id = id, storeId = STORE, name = "Ko'ylak")

    private fun category(id: String) = FashionCategory(id = id, name = "Ko'ylaklar")

    private fun cartItem(variantId: String, quantity: Int = 1) = FashionCartItem(
        variantId = variantId,
        storeId = STORE,
        productName = "Ko'ylak",
        quantity = quantity,
    )

    private companion object {
        const val STORE = "s-1"
    }
}
