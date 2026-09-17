package uz.mahalla.feature.pharmacy.data

import uz.mahalla.core.format.Money
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.map
import uz.mahalla.data.network.payload
import uz.mahalla.feature.pharmacy.domain.NewPharmacyProductDraft
import uz.mahalla.feature.pharmacy.domain.PharmacyProduct
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

    /**
     * Новый товар витрины (issue #252). Владелец правит своё заведение —
     * доступ проверяет бэкенд, клиент только не даёт заведомо невалидному
     * черновику уйти в сеть.
     */
    suspend fun createProduct(
        placeId: String,
        draft: NewPharmacyProductDraft,
    ): ApiResult<Unit>

    /** Остаток товара (issue #252). */
    suspend fun updateStock(
        placeId: String,
        productId: String,
        quantity: Int,
    ): ApiResult<PharmacyProduct>

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

    /**
     * Черновик уже проверен формой (`canSubmit`), но повторная проверка тут
     * — не подстраховка от опечатки, а защита от вызова репозитория в обход
     * экрана (как и у [uz.mahalla.feature.role.data.DefaultProviderRepository]).
     */
    override suspend fun createProduct(
        placeId: String,
        draft: NewPharmacyProductDraft,
    ): ApiResult<Unit> {
        val price = draft.priceSum
        if (placeId.isBlank() || !draft.canSubmit || price == null) {
            return ApiResult.Failure(
                ApiError.Business(NewPharmacyProductDraft.INVALID_CODE),
            )
        }

        return apiCall {
            api.create(
                placeId = placeId,
                body = CreateProductRequest(
                    name = draft.name.trim(),
                    manufacturer = draft.manufacturer.trim().takeIf(String::isNotEmpty),
                    description = draft.description.trim().takeIf(String::isNotEmpty),
                    dosageForm = draft.dosageForm.trim().takeIf(String::isNotEmpty),
                    strength = draft.strength.trim().takeIf(String::isNotEmpty),
                    price = Money.somToTiyin(price),
                    stockQuantity = draft.stockQuantity,
                    requiresPrescription = draft.requiresPrescription,
                ),
            ).payload()
        }.map {}
    }

    /**
     * Пустой `productId` или отрицательный остаток в сеть не уходят — тот же
     * приём, что и у пустого `placeId` в [products].
     */
    override suspend fun updateStock(
        placeId: String,
        productId: String,
        quantity: Int,
    ): ApiResult<PharmacyProduct> {
        if (placeId.isBlank() || productId.isBlank() || quantity < 0) {
            return ApiResult.Failure(
                ApiError.Business(PharmacyRepository.INVALID_REQUEST_CODE),
            )
        }

        val result = apiCall {
            api.updateStock(
                placeId = placeId,
                productId = productId,
                body = mapOf(STOCK_QUANTITY_KEY to quantity),
            ).payload()
        }
        return when (result) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> result.data.toDomain()?.let { ApiResult.Success(it) }
                // Товар только что обновлён по своему id — ответ без имени
                // или id был бы дефектом бэкенда, а не поводом промолчать.
                ?: ApiResult.Failure(ApiError.Business(PharmacyRepository.INVALID_REQUEST_CODE))
        }
    }

    private companion object {
        /**
         * Ключ карты `PUT products/{id}/stock` (issue #252) — схема
         * называет его безымянным `additionalProperties`, имя выведено из
         * `ProductResponse.stockQuantity`/`PharmacyCreateRequest.stockQuantity`
         * того же контроллера. Не проверено живым запросом — см. [PharmacyApi].
         */
        const val STOCK_QUANTITY_KEY = "stockQuantity"
    }
}
