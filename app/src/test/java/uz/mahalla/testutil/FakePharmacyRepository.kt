package uz.mahalla.testutil

import kotlinx.coroutines.CompletableDeferred
import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.pharmacy.data.PharmacyRepository
import uz.mahalla.feature.pharmacy.domain.NewPharmacyProductDraft
import uz.mahalla.feature.pharmacy.domain.PharmacyProduct
import uz.mahalla.feature.pharmacy.domain.PharmacyProductPage

/**
 * Витрина аптеки в памяти (issue #100): экран проверяется без MockWebServer.
 *
 * Ответ задаётся отдельно на каждую пару «запрос + страница» — иначе не
 * отличить догрузку от повторной загрузки первой и результаты одного поиска
 * от другого.
 */
class FakePharmacyRepository : PharmacyRepository {

    /** Ответ по паре «поисковый запрос → номер страницы»; иначе [defaultPage]. */
    val pages: MutableMap<Pair<String, Int>, ApiResult<PharmacyProductPage>> = mutableMapOf()

    var defaultPage: ApiResult<PharmacyProductPage> = ApiResult.Success(PharmacyProductPage())

    /** Что именно спрашивали — по порядку запросов. */
    val requests = mutableListOf<Request>()

    /**
     * Задержка ответа. Нужна тем проверкам, где важно **промежуточное**
     * состояние экрана (крутится ли индикатор обновления): `state` — это
     * `StateFlow`, и без точки приостановки внутри запроса он схлопнул бы
     * «начали» и «закончили» в одну эмиссию.
     */
    var gate: CompletableDeferred<Unit>? = null

    data class Request(val placeId: String, val query: String, val page: Int)

    override suspend fun products(
        placeId: String,
        query: String,
        page: Int,
        size: Int,
    ): ApiResult<PharmacyProductPage> {
        requests += Request(placeId = placeId, query = query, page = page)
        gate?.await()
        return pages[query to page] ?: defaultPage
    }

    /** Что именно отправили на создание — по порядку вызовов. */
    val createRequests = mutableListOf<Pair<String, NewPharmacyProductDraft>>()

    var createResult: ApiResult<Unit> = ApiResult.Success(Unit)

    override suspend fun createProduct(
        placeId: String,
        draft: NewPharmacyProductDraft,
    ): ApiResult<Unit> {
        createRequests += placeId to draft
        return createResult
    }

    /** Что именно отправили на правку остатка — по порядку вызовов. */
    val stockRequests = mutableListOf<Triple<String, String, Int>>()

    var stockResult: (String, Int) -> ApiResult<PharmacyProduct> = { productId, quantity ->
        ApiResult.Success(PharmacyProduct(id = productId, name = "", stockQuantity = quantity))
    }

    override suspend fun updateStock(
        placeId: String,
        productId: String,
        quantity: Int,
    ): ApiResult<PharmacyProduct> {
        stockRequests += Triple(placeId, productId, quantity)
        return stockResult(productId, quantity)
    }
}
