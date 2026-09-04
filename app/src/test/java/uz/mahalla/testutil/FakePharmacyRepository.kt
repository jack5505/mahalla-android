package uz.mahalla.testutil

import kotlinx.coroutines.CompletableDeferred
import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.pharmacy.data.PharmacyRepository
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
}
