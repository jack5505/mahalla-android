package uz.mahalla.feature.fashion.data

import javax.inject.Inject
import javax.inject.Singleton
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.data.network.ensureSuccess
import uz.mahalla.data.network.payload
import uz.mahalla.feature.fashion.domain.FashionCart
import uz.mahalla.feature.fashion.domain.FashionCartItem
import uz.mahalla.feature.fashion.domain.FashionCartRules
import uz.mahalla.feature.fashion.domain.FashionCatalogPage
import uz.mahalla.feature.fashion.domain.FashionCategory
import uz.mahalla.feature.fashion.domain.FashionProductDetail

/**
 * Каталог одежды (issue #108): категории, витрина магазина, карточка товара.
 *
 * Кэша нет намеренно: остатки по размерам меняются в течение дня, и «есть в
 * наличии» из Room — обещание вещи, которой уже нет.
 */
interface FashionRepository {

    suspend fun categories(): ApiResult<List<FashionCategory>>

    suspend fun catalog(
        storeId: String,
        categoryId: String? = null,
        page: Int = 0,
        size: Int = PAGE_SIZE,
    ): ApiResult<FashionCatalogPage>

    suspend fun product(productId: String): ApiResult<FashionProductDetail>

    companion object {
        const val PAGE_SIZE = 20
    }
}

@Singleton
class DefaultFashionRepository @Inject constructor(
    private val api: FashionApi,
) : FashionRepository {

    override suspend fun categories(): ApiResult<List<FashionCategory>> = apiCall {
        api.categories().payload().mapNotNull(FashionCategoryDto::toDomain)
    }

    override suspend fun catalog(
        storeId: String,
        categoryId: String?,
        page: Int,
        size: Int,
    ): ApiResult<FashionCatalogPage> = apiCall {
        api.catalog(
            storeId = storeId,
            // Пустая строка — не фильтр: `categoryId=` бэкенд разбирал бы как
            // битый uuid и отвечал `400 TYPE_MISMATCH`.
            categoryId = categoryId?.takeIf(String::isNotBlank),
            page = page.coerceAtLeast(0),
            size = size,
        ).payload().toDomain()
    }

    /**
     * Карточка товара. Товар без `id` — отказ разбора, а не пустая карточка:
     * положить в корзину нечего, а «товар без имени и без кнопки» человек
     * читает как поломку экрана.
     */
    override suspend fun product(productId: String): ApiResult<FashionProductDetail> {
        return when (val result = apiCall { api.product(productId).payload() }) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> result.data.toDomain()
                ?.let { ApiResult.Success(it) }
                ?: ApiResult.Failure(ApiError.Serialization)
        }
    }
}

/**
 * Корзина одежды — **на сервере** (issue #108).
 *
 * Это главное отличие от «Еды»: `CartRepository` с черновиком в Room
 * переиспользовать нельзя, офлайна здесь нет вовсе. Единственный ключ строки —
 * `variantId`; им адресуются и изменение количества, и удаление.
 *
 * Мутации возвращают `Unit`, а не новую корзину: ответы `PUT`/`DELETE` пусты
 * (`ApiResponseVoid`), а у `add` — одна строка, из которой всей корзины не
 * собрать. Что показывать после изменения, решает вызывающий: экран правит
 * строку на месте, а сомнительные случаи перечитывает [cart]'ом.
 */
interface FashionCartRepository {

    suspend fun cart(): ApiResult<FashionCart>

    /** Добавить вариант. Возвращается добавленная строка — её показывает экран товара. */
    suspend fun add(variantId: String, quantity: Int = 1): ApiResult<FashionCartItem>

    suspend fun setQuantity(variantId: String, quantity: Int): ApiResult<Unit>

    suspend fun remove(variantId: String): ApiResult<Unit>
}

@Singleton
class DefaultFashionCartRepository @Inject constructor(
    private val api: FashionApi,
) : FashionCartRepository {

    override suspend fun cart(): ApiResult<FashionCart> = apiCall {
        api.cart().payload().toCart()
    }

    /**
     * Добавление. Ответ разбирается мягко: строка без `variantId` — не отказ,
     * а «сервер ответил не тем». Товар при этом в корзине, поэтому наверх
     * уходит строка, собранная из того, что мы и просили: сказать «не
     * добавилось» о добавленном значит заставить нажать второй раз.
     */
    override suspend fun add(variantId: String, quantity: Int): ApiResult<FashionCartItem> {
        val requested = FashionCartRules.normalize(quantity)
        val result = apiCall {
            api.addToCart(AddToCartRequestDto(variantId = variantId, quantity = requested))
                .payload()
        }
        return when (result) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> ApiResult.Success(
                result.data.toDomain() ?: FashionCartItem(
                    variantId = variantId,
                    storeId = "",
                    productName = "",
                    quantity = requested,
                ),
            )
        }
    }

    /**
     * Новое количество. Ноль сюда не отправляется никогда: у удаления
     * отдельная ручка, а что сделает бэкенд с `quantity=0`, из контракта не
     * следует (issue #108).
     */
    override suspend fun setQuantity(variantId: String, quantity: Int): ApiResult<Unit> = apiCall {
        api.updateCartItem(
            variantId = variantId,
            quantity = FashionCartRules.normalize(quantity),
        ).ensureSuccess()
    }

    override suspend fun remove(variantId: String): ApiResult<Unit> = apiCall {
        api.removeCartItem(variantId).ensureSuccess()
    }
}
