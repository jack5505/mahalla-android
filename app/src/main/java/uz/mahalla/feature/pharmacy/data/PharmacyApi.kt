package uz.mahalla.feature.pharmacy.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
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
 * Двух остальных путей контроллера (`POST products`, `PUT products/{id}/stock`)
 * здесь нет намеренно: ими заведение правит свою витрину — это бизнес-панель,
 * эпик #16.
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
}

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
