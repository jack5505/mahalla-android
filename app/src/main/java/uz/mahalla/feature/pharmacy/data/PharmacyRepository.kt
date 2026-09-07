package uz.mahalla.feature.pharmacy.data

import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.map
import uz.mahalla.data.network.payload
import uz.mahalla.feature.pharmacy.domain.PharmacyProductPage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Витрина аптеки (issue #100).
 *
 * Кэша нет намеренно: смысл этого экрана — наличие, а «есть в наличии» из Room
 * после того, как лекарство разобрали, это ровно та ложь, ради избавления от
 * которой экран и делается. Пустой ответ сервера честнее устаревшего списка.
 *
 * Интерфейс — ради тестов ViewModel: экран проверяется без MockWebServer.
 */
interface PharmacyRepository {

    /**
     * Товары аптеки, страницами.
     *
     * @param query поиск на стороне сервера. Пустой не отправляется — параметр
     * необязательный, а `query=` в адресе запроса лишний повод для сервера
     * искать пустую строку.
     */
    suspend fun products(
        placeId: String,
        query: String = "",
        page: Int = 0,
        size: Int = PAGE_SIZE,
    ): ApiResult<PharmacyProductPage>

    companion object {
        /** Код отказа, когда спрашивать нечего ещё до запроса. */
        const val INVALID_REQUEST_CODE = "PHARMACY_REQUEST_INVALID"

        /** Столько же по умолчанию берёт и сам бэкенд. */
        const val PAGE_SIZE = 20
    }
}

@Singleton
class DefaultPharmacyRepository @Inject constructor(
    private val api: PharmacyApi,
) : PharmacyRepository {

    /**
     * Пустой `placeId` в сеть не уходит: бэкенд ответил бы `400 TYPE_MISMATCH`
     * (он ждёт uuid), но платой были бы запрос и молчание экрана на время его
     * выполнения.
     */
    override suspend fun products(
        placeId: String,
        query: String,
        page: Int,
        size: Int,
    ): ApiResult<PharmacyProductPage> {
        if (placeId.isBlank()) {
            return ApiResult.Failure(
                ApiError.Business(PharmacyRepository.INVALID_REQUEST_CODE),
            )
        }

        return apiCall {
            api.products(
                placeId = placeId,
                query = query.trim().takeIf { it.isNotEmpty() },
                page = page.coerceAtLeast(0),
                size = size,
            ).payload()
        }.map(ProductPageDto::toDomain)
    }
}
