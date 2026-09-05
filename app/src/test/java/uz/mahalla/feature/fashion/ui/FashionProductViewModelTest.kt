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
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.fashion.domain.FashionProductDetail
import uz.mahalla.feature.fashion.domain.ProductVariant
import uz.mahalla.feature.fashion.ui.product.FashionProductEvent
import uz.mahalla.feature.fashion.ui.product.FashionProductViewModel
import uz.mahalla.navigation.FashionArgs
import uz.mahalla.testutil.FakeFashionCartRepository
import uz.mahalla.testutil.FakeFashionRepository
import uz.mahalla.testutil.MainDispatcherRule

/**
 * Карточка товара одежды (issue #108): выбор варианта и добавление в
 * серверную корзину.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FashionProductViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val repository = FakeFashionRepository()
    private val cartRepository = FakeFashionCartRepository()

    @Test
    fun `first orderable variant is selected on open`() = runTest {
        repository.productResult = ApiResult.Success(
            detail(variant("v-1", stock = 0), variant("v-2")),
        )

        val state = viewModel().state.value

        assertEquals(listOf(PRODUCT), repository.requestedProducts)
        assertEquals("v-2", state.selectedVariantId)
        assertTrue(state.canAddToCart)
    }

    @Test
    fun `a product where nothing is in stock cannot be added`() = runTest {
        repository.productResult = ApiResult.Success(detail(variant("v-1", stock = 0)))
        val viewModel = viewModel()

        assertFalse(viewModel.state.value.canAddToCart)

        viewModel.onEvent(FashionProductEvent.AddToCartClicked)

        assertTrue(cartRepository.added.isEmpty())
    }

    @Test
    fun `changing the colour keeps the chosen size`() = runTest {
        repository.productResult = ApiResult.Success(
            detail(
                variant("v-1", color = "Oq", size = "S"),
                variant("v-2", color = "Oq", size = "L"),
                variant("v-3", color = "Qora", size = "S"),
                variant("v-4", color = "Qora", size = "L"),
            ),
        )
        val viewModel = viewModel()
        viewModel.onEvent(FashionProductEvent.VariantSelected("v-2"))

        viewModel.onEvent(FashionProductEvent.ColorSelected("Qora"))

        assertEquals("v-4", viewModel.state.value.selectedVariantId)
        assertEquals("Qora", viewModel.state.value.selectedColor)
    }

    @Test
    fun `adding sends the variant and confirms it on screen`() = runTest {
        repository.productResult = ApiResult.Success(detail(variant("v-1")))
        val viewModel = viewModel()

        viewModel.onEvent(FashionProductEvent.AddToCartClicked)

        assertEquals(listOf("v-1" to 1), cartRepository.added)
        // Молчаливый успех читается как «ничего не произошло» (issue #49).
        assertTrue(viewModel.state.value.added)
    }

    @Test
    fun `changing the choice drops the previous confirmation`() = runTest {
        repository.productResult = ApiResult.Success(
            detail(variant("v-1", size = "S"), variant("v-2", size = "L")),
        )
        val viewModel = viewModel()
        viewModel.onEvent(FashionProductEvent.AddToCartClicked)

        viewModel.onEvent(FashionProductEvent.VariantSelected("v-2"))

        // «Добавлено» рядом с другим размером сказало бы, что в корзине лежит
        // не то, что там лежит.
        assertFalse(viewModel.state.value.added)
    }

    @Test
    fun `a refused add keeps the card and shows the server text`() = runTest {
        repository.productResult = ApiResult.Success(detail(variant("v-1")))
        cartRepository.addResult = ApiResult.Failure(ApiError.Business("OUT_OF_STOCK"))
        val viewModel = viewModel()

        viewModel.onEvent(FashionProductEvent.AddToCartClicked)

        val state = viewModel.state.value
        assertTrue(state.product is ScreenState.Content)
        assertEquals(ApiError.Business("OUT_OF_STOCK"), state.addFailure?.error)
        assertFalse(state.added)
    }

    @Test
    fun `an unknown product is an error with a retry`() = runTest {
        repository.productResult = ApiResult.Failure(ApiError.NotFound)
        val viewModel = viewModel()
        assertTrue(viewModel.state.value.product is ScreenState.Error)

        repository.productResult = ApiResult.Success(detail(variant("v-1")))
        viewModel.onEvent(FashionProductEvent.Retry)

        assertTrue(viewModel.state.value.product is ScreenState.Content)
        assertEquals(2, repository.requestedProducts.size)
    }

    private fun viewModel() = FashionProductViewModel(
        repository = repository,
        cartRepository = cartRepository,
        savedStateHandle = SavedStateHandle(mapOf(FashionArgs.PRODUCT_ID to PRODUCT)),
    )

    private fun detail(vararg variants: ProductVariant) = FashionProductDetail(
        id = PRODUCT,
        storeId = "s-1",
        name = "Ko'ylak",
        basePriceSum = 240_000,
        variants = variants.toList(),
    )

    private fun variant(
        id: String,
        color: String = "Oq",
        size: String = "M",
        stock: Int? = null,
    ) = ProductVariant(
        id = id,
        colorName = color,
        size = size,
        priceSum = 240_000,
        stockQuantity = stock,
    )

    private companion object {
        const val PRODUCT = "p-1"
    }
}
