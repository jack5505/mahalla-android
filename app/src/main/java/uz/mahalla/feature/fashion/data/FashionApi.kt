package uz.mahalla.feature.fashion.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import uz.mahalla.data.network.ApiResponse
import uz.mahalla.feature.food.data.CreatedOrderDto
import uz.mahalla.feature.food.data.OrderViewDto

/**
 * Вертикаль «Одежда» (issue #108) — самый большой контроллер бэкенда.
 *
 * Контракт снят со стенда (`/v3/api-docs` + прямые curl'ы 2026-09-05):
 *
 * - **Каталог анонимен**: категории и каталог магазина отвечают `200` без
 *   токена, неизвестный товар — `404 NOT_FOUND`. Всё, что про корзину и
 *   заказы, требует Bearer (`401`).
 * - Гео-заголовки обязательны на всех путях (без них `403
 *   GEO_PERMISSION_REQUIRED`), но их ставит `GeoHeaderInterceptor` (issue
 *   #53).
 * - `storeId` и `id` товара — **uuid**: числовой id отвечает
 *   `400 TYPE_MISMATCH`.
 *
 * API целиком собирается на **основном** Retrofit: лишний заголовок
 * анонимным ручкам не мешает, а «голый» `@RefreshClient` сломал бы корзину.
 *
 * **Заказы читаются общим `orders`-контроллером, а не путями `fashion/orders`.**
 * Ответ фэшн-заказа описан схемой `OrderResponse`, а это имя в `/v3/api-docs`
 * перекрыто коллизией springdoc (16 путей, показан вариант заказа
 * фрилансера — `freelancerId`, `serviceId`, `serviceTitle`), то есть имена
 * полей оттуда взять нельзя. У общего `OrderView` схема однозначна, а
 * `CLOTHING` входит в его `vertical`. Ровно то же решение принято для «Еды»
 * (issue #9).
 */
interface FashionApi {

    /**
     * Категории одежды. Справочник общий: параметров эндпоинт не принимает
     * вовсе, магазин здесь ни при чём.
     */
    @GET("fashion/categories")
    suspend fun categories(): ApiResponse<List<FashionCategoryDto>>

    /**
     * Каталог магазина. Кроме [categoryId] бэкенд принимает `gender`, `brand`,
     * `minPrice`, `maxPrice` и `sort` — они не отправляются: в приложении нет
     * экрана, где их выбирают, а параметр, которого никто не задаёт, только
     * путал бы чтение запроса в инспекторе.
     */
    @GET("fashion/stores/{storeId}/catalog")
    suspend fun catalog(
        @Path("storeId") storeId: String,
        @Query("categoryId") categoryId: String?,
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ApiResponse<CatalogDto>

    @GET("fashion/products/{id}")
    suspend fun product(@Path("id") productId: String): ApiResponse<ProductDetailDto>

    /**
     * Новый товар магазина (issue #280, продолжение #252). Тело —
     * `ProductCreateRequest`, поля выведены по аналогии с уже подтверждёнными
     * полями [ProductDetailDto]/[ProductSummaryDto] того же контроллера
     * (`name`, `description`, `brand`, `material`, `careInstructions`,
     * `sizeGuide`, `gender`, `categoryId`, `basePrice`), а не угаданы по
     * другой вертикали. Не проверено живым запросом — нужен Bearer владельца
     * заведения, `CONTRACT_REFRESH_TOKEN` в песочнице не задан; риск —
     * `docs/API-CONTRACT.md`. Ответ не разбирается дальше `success`
     * (`ensureSuccess`, не `payload`) — точная схема тела `POST`-ответа не
     * подтверждена, а список/карточка перечитываются отдельным `GET`.
     */
    @POST("fashion/stores/{storeId}/products")
    suspend fun createProduct(
        @Path("storeId") storeId: String,
        @Body body: CreateFashionProductRequest,
    ): ApiResponse<JsonElement>

    /**
     * Новый вариант товара — размер/цвет (issue #280). Тело —
     * `VariantCreateRequest`, поля выведены по аналогии с уже подтверждёнными
     * полями [VariantDto] (`colorName`, `colorHex`, `size`, `sku`, `price`,
     * `stockQuantity`). Не проверено живым запросом — та же причина, что и у
     * [createProduct], включая `ensureSuccess` вместо `payload`.
     */
    @POST("fashion/products/{id}/variants")
    suspend fun createVariant(
        @Path("id") productId: String,
        @Body body: CreateFashionVariantRequest,
    ): ApiResponse<JsonElement>

    /** Корзина на сервере: один список на все магазины. */
    @GET("fashion/cart")
    suspend fun cart(): ApiResponse<List<CartItemDto>>

    @POST("fashion/cart/add")
    suspend fun addToCart(@Body body: AddToCartRequestDto): ApiResponse<CartItemDto>

    /**
     * Новое количество строки. Количество идёт **query-параметром**, а не
     * телом — так объявлен контракт.
     */
    @PUT("fashion/cart/{variantId}")
    suspend fun updateCartItem(
        @Path("variantId") variantId: String,
        @Query("quantity") quantity: Int,
    ): ApiResponse<JsonElement>

    @DELETE("fashion/cart/{variantId}")
    suspend fun removeCartItem(@Path("variantId") variantId: String): ApiResponse<JsonElement>

    /**
     * Оформление. Своя схема, не общая с «Едой» (issue #221, найдено при
     * сверке в issue #167): у [FashionPlaceOrderRequestDto] обязателен
     * `storeId`, а не `placeId`, и `items` в теле нет вовсе — состав заказа
     * сервер берёт из своей корзины (`fashion/cart*`). Из ответа разбирается
     * только идентификатор: см. KDoc интерфейса.
     */
    @POST("fashion/orders")
    suspend fun createOrder(@Body body: FashionPlaceOrderRequestDto): ApiResponse<CreatedOrderDto>

    /**
     * Свои заказы. `fashion/orders/my` отдаёт то же самое, но в перекрытой
     * коллизией схеме — поэтому идём в общий список с фильтром по вертикали.
     *
     * [vertical] нулевой — фильтра нет, и приезжают заказы **всех**
     * вертикалей (`FOOD`, `CLOTHING`, `PHARMACY`, `CINEMA`, `GAMING`): так их
     * читают «Мои активности» (issue #73). Retrofit нулевой `@Query` в URL не
     * ставит вовсе, так что для одежды запрос не меняется.
     */
    @GET("orders")
    suspend fun myOrders(
        @Query("vertical") vertical: String?,
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ApiResponse<OrderPageDto>

    @GET("orders/{orderId}")
    suspend fun order(@Path("orderId") orderId: String): ApiResponse<OrderViewDto>

    /**
     * Отмена. Тело ответа не разбирается (та же коллизия схемы) — новое
     * состояние заказа вызывающий перечитывает [order]'ом. Иначе неудачный
     * разбор ответа выглядел бы как «отменить не удалось», хотя заказ уже
     * отменён.
     */
    @POST("fashion/orders/{orderId}/cancel")
    suspend fun cancelOrder(@Path("orderId") orderId: String): ApiResponse<JsonElement>

    /**
     * Входящие заказы магазина (бизнес-панель, issue #187). Путь и схема
     * сняты живым `/v3/api-docs` **2026-09-19** (`storeOrders` в
     * `fashion-controller`): `status` — то же перечисление `OrderStatus`, что
     * и у «Еды» (`NEW`, `ACCEPTED`, `PREPARING`, `READY`, `IN_DELIVERY`,
     * `DELIVERED`, `CANCELLED`, `REFUNDED`, см.
     * `uz.mahalla.feature.food.domain.OrderStatus`) — заводить второе
     * перечисление под ту же вертикаль не пришлось.
     */
    @GET("fashion/stores/{storeId}/orders")
    suspend fun storeOrders(
        @Path("storeId") storeId: String,
        @Query("status") status: String?,
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ApiResponse<FashionStoreOrderPageDto>

    /**
     * Сменить статус заказа.
     *
     * Тело — `Map<String, String>`; ключ **`status`** — тот же вывод, что и у
     * `BusinessApi.updateOrderStatus` («Еда»): своей схемы у ручки нет, а
     * операция совпадает с соседними вертикалями бэкенда.
     *
     * `storeId` в параметрах операции у `/v3/api-docs` не значится — тот же
     * класс дефекта схемы, что у трёх безымянных `Map`-тел `BusinessApi`, —
     * но путь его требует буквально, и Retrofit обязан подставить значение,
     * иначе URL уедет с `{storeId}` внутри.
     */
    @PUT("fashion/stores/{storeId}/orders/{orderId}/status")
    suspend fun updateStoreOrderStatus(
        @Path("storeId") storeId: String,
        @Path("orderId") orderId: String,
        @Body body: Map<String, String>,
    ): ApiResponse<FashionStoreOrderDto>
}

@Serializable
data class FashionCategoryDto(
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("iconUrl") val iconUrl: String? = null,
)

/** `CatalogResponse`: страница без флага `last` — конец считается по `totalPages`. */
@Serializable
data class CatalogDto(
    @SerialName("products") val products: List<ProductSummaryDto> = emptyList(),
    @SerialName("page") val page: Int? = null,
    @SerialName("totalPages") val totalPages: Int? = null,
    @SerialName("totalElements") val totalElements: Long? = null,
)

@Serializable
data class ProductSummaryDto(
    @SerialName("id") val id: String? = null,
    @SerialName("storeId") val storeId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("brand") val brand: String? = null,
    @SerialName("gender") val gender: String? = null,
    @SerialName("basePrice") val basePrice: Long? = null,
    @SerialName("salePrice") val salePrice: Long? = null,
    @SerialName("ratingAvg") val ratingAvg: Double? = null,
    @SerialName("ratingCount") val ratingCount: Int? = null,
    @SerialName("isNew") val isNew: Boolean? = null,
    @SerialName("new") val new: Boolean? = null,
    @SerialName("isBestseller") val isBestseller: Boolean? = null,
    @SerialName("bestseller") val bestseller: Boolean? = null,
)

/**
 * `ProductDetail`.
 *
 * [variantsByColor] — карта «цвет → варианты» (`additionalProperties` в
 * схеме). Пустая карта вместо `null` обязательна: товар без вариантов
 * показать можно, а уронить на нём разбор — нет.
 */
@Serializable
data class ProductDetailDto(
    @SerialName("id") val id: String? = null,
    @SerialName("storeId") val storeId: String? = null,
    @SerialName("categoryId") val categoryId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("brand") val brand: String? = null,
    @SerialName("material") val material: String? = null,
    @SerialName("careInstructions") val careInstructions: String? = null,
    @SerialName("sizeGuide") val sizeGuide: String? = null,
    @SerialName("gender") val gender: String? = null,
    @SerialName("basePrice") val basePrice: Long? = null,
    @SerialName("salePrice") val salePrice: Long? = null,
    @SerialName("ratingAvg") val ratingAvg: Double? = null,
    @SerialName("ratingCount") val ratingCount: Int? = null,
    @SerialName("isNew") val isNew: Boolean? = null,
    @SerialName("new") val new: Boolean? = null,
    @SerialName("isBestseller") val isBestseller: Boolean? = null,
    @SerialName("bestseller") val bestseller: Boolean? = null,
    @SerialName("variantsByColor") val variantsByColor: Map<String, List<VariantDto>> = emptyMap(),
)

/**
 * `VariantResponse`.
 *
 * [images] в домен не доезжает: загрузчика изображений в проекте нет (#60),
 * а формат самого поля контракт не описывает (`string` под множественным
 * именем — одна ссылка это или список, неизвестно). Объявлено, чтобы
 * документировать контракт, — так же поступили с `imageUrl` уведомления
 * (issue #81).
 */
@Serializable
data class VariantDto(
    @SerialName("id") val id: String? = null,
    @SerialName("colorName") val colorName: String? = null,
    @SerialName("colorHex") val colorHex: String? = null,
    @SerialName("size") val size: String? = null,
    @SerialName("sku") val sku: String? = null,
    @SerialName("images") val images: String? = null,
    @SerialName("price") val price: Long? = null,
    @SerialName("stockQuantity") val stockQuantity: Int? = null,
    @SerialName("isAvailable") val isAvailable: Boolean? = null,
    @SerialName("available") val available: Boolean? = null,
)

/**
 * `ProductCreateRequest` (issue #280). Обязательны только [name] и
 * [basePrice] — остальное схема не ограничивает. Пустые необязательные поля
 * не уходят вовсе (`explicitNulls = false`, issue #84).
 *
 * @param basePrice в тийинах, как и [ProductDetailDto.basePrice] (issue
 * #149); черновик считает в сумах, перевод делает репозиторий.
 */
@Serializable
data class CreateFashionProductRequest(
    @SerialName("name") val name: String,
    @SerialName("description") val description: String? = null,
    @SerialName("brand") val brand: String? = null,
    @SerialName("material") val material: String? = null,
    @SerialName("careInstructions") val careInstructions: String? = null,
    @SerialName("sizeGuide") val sizeGuide: String? = null,
    @SerialName("gender") val gender: String? = null,
    @SerialName("categoryId") val categoryId: String? = null,
    @SerialName("basePrice") val basePrice: Long,
)

/**
 * `VariantCreateRequest` (issue #280). Обязательны [colorName], [size] и
 * [price] — вариант без них нечем отличить от соседнего и нечем продать.
 *
 * @param price в тийинах, как и [VariantDto.price] (issue #149).
 */
@Serializable
data class CreateFashionVariantRequest(
    @SerialName("colorName") val colorName: String,
    @SerialName("colorHex") val colorHex: String? = null,
    @SerialName("size") val size: String,
    @SerialName("sku") val sku: String? = null,
    @SerialName("price") val price: Long,
    @SerialName("stockQuantity") val stockQuantity: Int? = null,
)

/** `CartItemResponse`. */
@Serializable
data class CartItemDto(
    @SerialName("id") val id: String? = null,
    @SerialName("variantId") val variantId: String? = null,
    @SerialName("storeId") val storeId: String? = null,
    @SerialName("productName") val productName: String? = null,
    @SerialName("colorName") val colorName: String? = null,
    @SerialName("size") val size: String? = null,
    @SerialName("imageUrl") val imageUrl: String? = null,
    @SerialName("unitPrice") val unitPrice: Long? = null,
    @SerialName("quantity") val quantity: Int? = null,
    @SerialName("totalPrice") val totalPrice: Long? = null,
)

/** `AddToCartRequest`: обязателен только вариант, количество — по умолчанию 1. */
@Serializable
data class AddToCartRequestDto(
    @SerialName("variantId") val variantId: String,
    @SerialName("quantity") val quantity: Int,
)

/**
 * `FashionPlaceOrderRequest` — своя схема, не общая с «Едой» (issue #221):
 * обязателен [storeId], а не `placeId`, и поля `items` нет вовсе — состав
 * заказа сервер берёт из своей корзины (`fashion/cart*`), которую клиент уже
 * ведёт.
 *
 * Схема допускает ещё `deliveryLat`/`deliveryLng` — клиент их не шлёт: на
 * экране оформления нет выбора точки на карте, а угаданные координаты хуже,
 * чем их отсутствие. `promoCode` шлётся: проверенный код из чекаута (issue
 * #180, `GET promotions/check`).
 */
@Serializable
data class FashionPlaceOrderRequestDto(
    @SerialName("storeId") val storeId: String,
    /** `DELIVERY` / `PICKUP` / `DINE_IN`. */
    @SerialName("fulfillment") val fulfillment: String,
    /** `WALLET` / `CASH`. */
    @SerialName("paymentMethod") val paymentMethod: String,
    @SerialName("deliveryAddress") val deliveryAddress: String? = null,
    @SerialName("promoCode") val promoCode: String? = null,
)

/**
 * `PageResponseOrderView` — страница общего списка заказов.
 *
 * Объявлена здесь, а не в «Еде»: там страничного списка заказов нет вовсе
 * («мои заказы» появились первыми у одежды). Схема общая для всех вертикалей.
 */
@Serializable
data class OrderPageDto(
    @SerialName("content") val content: List<OrderViewDto> = emptyList(),
    @SerialName("page") val page: Int? = null,
    @SerialName("size") val size: Int? = null,
    @SerialName("totalElements") val totalElements: Long? = null,
    @SerialName("totalPages") val totalPages: Int? = null,
    @SerialName("first") val first: Boolean? = null,
    @SerialName("last") val last: Boolean? = null,
)

/** `PageResponseFashionOrderResponse` — страница заказов магазина (issue #187). */
@Serializable
data class FashionStoreOrderPageDto(
    @SerialName("content") val content: List<FashionStoreOrderDto> = emptyList(),
    @SerialName("page") val page: Int? = null,
    @SerialName("size") val size: Int? = null,
    @SerialName("totalElements") val totalElements: Long? = null,
    @SerialName("totalPages") val totalPages: Int? = null,
    @SerialName("first") val first: Boolean? = null,
    @SerialName("last") val last: Boolean? = null,
)

/**
 * `FashionOrderResponse` — заказ глазами магазина (issue #187). Полей меньше,
 * чем у `FoodOrderResponse`: `staffId` нет вовсе. `placeName`/`placeLogoUrl`
 * из схемы не разбираются — панели своё имя заведения уже известно.
 */
@Serializable
data class FashionStoreOrderDto(
    @SerialName("id") val id: String? = null,
    @SerialName("storeId") val storeId: String? = null,
    @SerialName("userId") val userId: String? = null,
    @SerialName("orderNumber") val orderNumber: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("fulfillment") val fulfillment: String? = null,
    @SerialName("paymentMethod") val paymentMethod: String? = null,
    @SerialName("deliveryAddress") val deliveryAddress: String? = null,
    @SerialName("itemsAmount") val itemsAmount: Long? = null,
    @SerialName("deliveryAmount") val deliveryAmount: Long? = null,
    @SerialName("discountAmount") val discountAmount: Long? = null,
    @SerialName("totalAmount") val totalAmount: Long? = null,
    @SerialName("items") val items: List<FashionStoreOrderItemDto> = emptyList(),
    @SerialName("createdAt") val createdAt: String? = null,
)

/**
 * `FashionOrderItemResponse`: строка по варианту, а не по позиции меню —
 * `variantId`/`colorName`/`size` вместо `itemId`/`itemName` «Еды».
 */
@Serializable
data class FashionStoreOrderItemDto(
    @SerialName("variantId") val variantId: String? = null,
    @SerialName("productName") val productName: String? = null,
    @SerialName("colorName") val colorName: String? = null,
    @SerialName("size") val size: String? = null,
    @SerialName("quantity") val quantity: Int? = null,
    @SerialName("unitPrice") val unitPrice: Long? = null,
    @SerialName("totalPrice") val totalPrice: Long? = null,
)
