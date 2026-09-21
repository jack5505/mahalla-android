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

    /** Что именно отправили на правку товара — по порядку вызовов (issue #288). */
    val updateRequests = mutableListOf<Triple<String, String, NewPharmacyProductDraft>>()

    var updateResult: (String, NewPharmacyProductDraft) -> ApiResult<PharmacyProduct> =
        { productId, draft ->
            ApiResult.Success(
                PharmacyProduct(id = productId, name = draft.name, priceSum = draft.priceSum),
            )
        }

    override suspend fun updateProduct(
        placeId: String,
        productId: String,
        draft: NewPharmacyProductDraft,
    ): ApiResult<PharmacyProduct> {
        updateRequests += Triple(placeId, productId, draft)
        return updateResult(productId, draft)
    }

    /** Что именно удалили — по порядку вызовов (issue #288). */
    val deleteRequests = mutableListOf<Pair<String, String>>()

    var deleteResult: ApiResult<Unit> = ApiResult.Success(Unit)

    /**
     * Задержка ответа по конкретному товару — нужна проверке, что удаление
     * одного товара не сбивает отметку «удаляется» у другого, ещё не
     * ответившего (issue #288). Без ключа по id один общий гейт держал бы оба
     * запроса одной и той же задержкой.
     */
    val deleteGates: MutableMap<String, CompletableDeferred<Unit>> = mutableMapOf()

    override suspend fun deleteProduct(placeId: String, productId: String): ApiResult<Unit> {
        deleteRequests += placeId to productId
        deleteGates[productId]?.await()
        return deleteResult
    }
}
