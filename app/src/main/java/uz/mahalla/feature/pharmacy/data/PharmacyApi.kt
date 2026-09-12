package uz.mahalla.feature.pharmacy.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import uz.mahalla.data.network.ApiResponse

/**
 * Витрина аптеки (issue #100, `pharmacy-controller`).
 *
 * Контракт снят со стенда (`/v3/api-docs` + прямые curl'ы 2026-09-04):
 *
 * | проверка | ответ |
 * |---|---|
 * | без гео-заголовков | `403 GEO_PERMISSION_REQUIRED` |
 * | с гео, **без токена** | `200`, `data` — страница `PageResponseProductResponse` |
 * | `?query=aspirin&page=2&size=5` | `200`, `page: 2`, `size: 5` |
 * | `placeId=1` (не uuid) | `400 TYPE_MISMATCH` |
 *
 * Из этого следуют три вещи:
 *
 * - **Витрина анонимна.** Товары видны и до входа; Bearer ей не мешает,
 *   поэтому API собирается на **основном** Retrofit (разводить его по двум
 *   клиентам незачем, а «голый» `@RefreshClient` понадобился бы только тому,
 *   что ходит без токена намеренно).
 * - **Гео-заголовки обязательны**, но их уже ставит `GeoHeaderInterceptor`
 *   на обоих клиентах (issue #53) — отдельной заботы тут нет.
 * - **Пагинация и серверный поиск есть.** Issue просила проверить, нет ли их;
 *   они есть, и это меняет решение: фильтровать по приехавшему списку нельзя,
 *   иначе совпадения на непрогруженных страницах остались бы невидимыми.
 *
 * `POST products` и `PUT products/{id}/stock` (issue #252, владелец правит
 * свою витрину) сняты той же схемой `/v3/api-docs` 2026-09-11, но не
 * подтверждены живым запросом — обе требуют Bearer и роли владельца
 * заведения, а `CONTRACT_REFRESH_TOKEN` в песочнице не задан. Подробности и
 * риск — `docs/API-CONTRACT.md`.
 */
interface PharmacyApi {

    /**
     * Товары аптеки, страницами.
     *
     * @param query поиск на стороне сервера. По каким полям он ищет, контракт
     * не документирует — известно только, что параметр принимается. Пустую
     * строку не отправляем вовсе (см. [DefaultPharmacyRepository]).
     */
    @GET("pharmacy/places/{placeId}/products")
    suspend fun products(
        @Path("placeId") placeId: String,
        @Query("query") query: String?,
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ApiResponse<ProductPageDto>

    /**
     * Новый товар витрины (issue #252). Тело — `PharmacyCreateRequest`, имя в
     * `/v3/api-docs` коллизией springdoc не перекрыто (встречается только у
     * этого пути), поля читаны из схемы как есть.
     */
    @POST("pharmacy/places/{placeId}/products")
    suspend fun create(
        @Path("placeId") placeId: String,
        @Body body: CreateProductRequest,
    ): ApiResponse<ProductDto>

    /**
     * Остаток товара (issue #252). Тело в схеме объявлено безымянной картой
     * (`additionalProperties: integer`, как `walkin/accept` в PR #161 и
     * `reviews/{id}/reply` в issue #188) — сгенерировано из
     * `Map<String, Int>` в контроллере, имени ключа схема не называет.
     *
     * Ключ **выведен из соседних схем того же контроллера**: и
     * `ProductResponse`, и `PharmacyCreateRequest` называют это поле
     * `stockQuantity` — отправляем `{"stockQuantity": N}`. Не проверено живым
     * запросом (нужен Bearer владельца, `CONTRACT_REFRESH_TOKEN` в песочнице
     * нет) — при расхождении смотреть `docs/API-CONTRACT.md` в первую
     * очередь.
     */
    @PUT("pharmacy/places/{placeId}/products/{id}/stock")
    suspend fun updateStock(
        @Path("placeId") placeId: String,
        @Path("id") productId: String,
        @Body body: Map<String, Int>,
    ): ApiResponse<ProductDto>
}

/**
 * `PharmacyCreateRequest`. Обязательны только [name] (≤ 300) и [price] —
 * остальное схема не ограничивает. Пустые необязательные поля не уходят
 * вовсе (`explicitNulls = false` в конфигурации Json, issue #84).
 *
 * @param price в тийинах — как и [ProductDto.price] (issue #149); черновик
 * считает в сумах, перевод делает репозиторий.
 */
@Serializable
data class CreateProductRequest(
    @SerialName("name") val name: String,
    @SerialName("manufacturer") val manufacturer: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("dosageForm") val dosageForm: String? = null,
    @SerialName("strength") val strength: String? = null,
    @SerialName("price") val price: Long,
    @SerialName("stockQuantity") val stockQuantity: Int? = null,
    @SerialName("requiresPrescription") val requiresPrescription: Boolean? = null,
)

/**
 * `ProductResponse`. Имя в схеме встречается только в путях самого
 * `pharmacy-controller` — коллизии springdoc нет, поля прочитаны как есть.
 *
 * Все поля необязательные: отсутствие любого из них — не повод показать экран
 * ошибки вместо витрины.
 *
 * [isAvailable] и [requiresPrescription] принимаются и под именами без
 * префикса `is`: Jackson сериализует `boolean isAvailable` то так, то так, в
 * зависимости от геттера. Ошибка здесь показала бы «нет в наличии» у всей
 * аптеки — то же правило, что у `isRead` в issue #81 и `isAvailable` в
 * issue #94.
 */
@Serializable
data class ProductDto(
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("manufacturer") val manufacturer: String? = null,
    @SerialName("dosageForm") val dosageForm: String? = null,
    @SerialName("strength") val strength: String? = null,
    @SerialName("price") val price: Long? = null,
    @SerialName("stockQuantity") val stockQuantity: Int? = null,
    @SerialName("isAvailable") val isAvailable: Boolean? = null,
    @SerialName("available") val available: Boolean? = null,
    @SerialName("requiresPrescription") val requiresPrescription: Boolean? = null,
    @SerialName("prescriptionRequired") val prescriptionRequired: Boolean? = null,
)

/** `PageResponseProductResponse`. */
@Serializable
data class ProductPageDto(
    @SerialName("content") val content: List<ProductDto> = emptyList(),
    @SerialName("page") val page: Int? = null,
    @SerialName("size") val size: Int? = null,
    @SerialName("totalElements") val totalElements: Long? = null,
    @SerialName("totalPages") val totalPages: Int? = null,
    @SerialName("first") val first: Boolean? = null,
    @SerialName("last") val last: Boolean? = null,
)
