package uz.mahalla.testutil

import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.fashion.data.FashionCartRepository
import uz.mahalla.feature.fashion.data.FashionOrderRepository
import uz.mahalla.feature.fashion.data.FashionRepository
import uz.mahalla.feature.fashion.domain.FashionCart
import uz.mahalla.feature.fashion.domain.FashionCartItem
import uz.mahalla.feature.fashion.domain.FashionCartStore
import uz.mahalla.feature.fashion.domain.FashionCatalogPage
import uz.mahalla.feature.fashion.domain.FashionCategory
import uz.mahalla.feature.fashion.domain.FashionOrderPage
import uz.mahalla.feature.fashion.domain.FashionProductDetail
import uz.mahalla.feature.food.domain.CheckoutForm
import uz.mahalla.feature.food.domain.Order

/**
 * Вертикаль «Одежда» в памяти (issue #108): экраны проверяются без
 * MockWebServer.
 *
 * Ответ на каждую страницу задаётся отдельно — иначе не отличить догрузку от
 * повторной загрузки первой страницы.
 */
class FakeFashionRepository : FashionRepository {

    var categoriesResult: ApiResult<List<FashionCategory>> = ApiResult.Success(emptyList())

    /** Страницы каталога по номеру; иначе [defaultCatalog]. */
    val catalogPages: MutableMap<Int, ApiResult<FashionCatalogPage>> = mutableMapOf()

    var defaultCatalog: ApiResult<FashionCatalogPage> = ApiResult.Success(FashionCatalogPage())

    /** Что именно спрашивали: магазин, категория, страница — по порядку. */
    val catalogRequests = mutableListOf<CatalogRequest>()

    var productResult: ApiResult<FashionProductDetail>? = null

    val requestedProducts = mutableListOf<String>()

    data class CatalogRequest(val storeId: String, val categoryId: String?, val page: Int)

    override suspend fun categories(): ApiResult<List<FashionCategory>> = categoriesResult

    override suspend fun catalog(
        storeId: String,
        categoryId: String?,
        page: Int,
        size: Int,
    ): ApiResult<FashionCatalogPage> {
        catalogRequests += CatalogRequest(storeId, categoryId, page)
        return catalogPages[page] ?: defaultCatalog
    }

    override suspend fun product(productId: String): ApiResult<FashionProductDetail> {
        requestedProducts += productId
        return productResult ?: ApiResult.Success(
            FashionProductDetail(id = productId, storeId = "s-1", name = "Tovar"),
        )
    }
}

class FakeFashionCartRepository : FashionCartRepository {

    var cartResult: ApiResult<FashionCart> = ApiResult.Success(FashionCart())

    var addResult: ApiResult<FashionCartItem>? = null
    var setQuantityResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var removeResult: ApiResult<Unit> = ApiResult.Success(Unit)

    var cartRequests = 0
    val added = mutableListOf<Pair<String, Int>>()
    val quantities = mutableListOf<Pair<String, Int>>()
    val removed = mutableListOf<String>()

    override suspend fun cart(): ApiResult<FashionCart> {
        cartRequests++
        return cartResult
    }

    override suspend fun add(variantId: String, quantity: Int): ApiResult<FashionCartItem> {
        added += variantId to quantity
        return addResult ?: ApiResult.Success(
            FashionCartItem(
                variantId = variantId,
                storeId = "s-1",
                productName = "Tovar",
                quantity = quantity,
            ),
        )
    }

    override suspend fun setQuantity(variantId: String, quantity: Int): ApiResult<Unit> {
        quantities += variantId to quantity
        return setQuantityResult
    }

    override suspend fun remove(variantId: String): ApiResult<Unit> {
        removed += variantId
        return removeResult
    }
}

class FakeFashionOrderRepository : FashionOrderRepository {

    var createResult: ApiResult<String> = ApiResult.Success("o-1")

    /** Что ушло в заказ: магазин, пары «вариант → количество» и форма. */
    val created = mutableListOf<CreatedOrder>()

    val pages: MutableMap<Int, ApiResult<FashionOrderPage>> = mutableMapOf()

    var defaultPage: ApiResult<FashionOrderPage> = ApiResult.Success(FashionOrderPage())

    val requestedPages = mutableListOf<Int>()

    var cancelResult: ApiResult<Unit> = ApiResult.Success(Unit)

    val cancelled = mutableListOf<String>()

    /** Что вернёт перечит заказа после отмены; `null` — отказ. */
    var orderResult: ApiResult<Order>? = null

    val requestedOrders = mutableListOf<String>()

    data class CreatedOrder(
        val storeId: String,
        val items: List<Pair<String, Int>>,
        val form: CheckoutForm,
    )

    override suspend fun create(
        store: FashionCartStore,
        form: CheckoutForm,
    ): ApiResult<String> {
        created += CreatedOrder(
            storeId = store.storeId,
            items = store.items.map { it.variantId to it.quantity },
            form = form,
        )
        return createResult
    }

    override suspend fun myOrders(page: Int, size: Int): ApiResult<FashionOrderPage> {
        requestedPages += page
        return pages[page] ?: defaultPage
    }

    override suspend fun order(orderId: String): ApiResult<Order> {
        requestedOrders += orderId
        return orderResult ?: ApiResult.Failure(ApiError.NoConnection)
    }

    override suspend fun cancel(orderId: String): ApiResult<Unit> {
        cancelled += orderId
        return cancelResult
    }
}
