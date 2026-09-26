package uz.mahalla.feature.discovery.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import uz.mahalla.data.network.ApiResponse

/**
 * Категория плитки главной (`CategoryItem` в схеме бэкенда, issue #378 /
 * jack5505/mahalla#338).
 *
 * [code] — то же значение перечисления, что принимает параметр `category` в
 * `places/nearby`, `places/map-bounds` и `search`; сопоставляется с
 * `PlaceCategory.fromApi`. Код, которого в приложении нет (`BAKERY`, `SHOP`,
 * `MUSEUM`…), по контракту приехать может — его пропускают, а не роняют список.
 *
 * Подписи [titleUz]/[titleRu] сервер отдаёт, но плитка рисуется по своим
 * строкам (`PlaceCategory.labelRes`): иконка и подпись подбираются по коду.
 * Поля разбираются и складываются в кэш, чтобы переход на серверные подписи
 * не требовал новой миграции.
 */
@Serializable
data class CategoryDto(
    @SerialName("code") val code: String = "",
    @SerialName("titleUz") val titleUz: String? = null,
    @SerialName("titleRu") val titleRu: String? = null,
    @SerialName("sortOrder") val sortOrder: Int = 0,
)

/**
 * Каталог категорий (issue #378). Контракт — jack5505/mahalla#340:
 * `GET /api/v1/categories`, публичная ручка без JWT и без `X-Geo-*`, отдаёт
 * только включённые в дашборде категории в порядке `sortOrder`. Ответ в
 * стандартном конверте, `Cache-Control: max-age=3600` и `ETag`.
 *
 * Кнопка «Все» на главной — клиентская, в список не входит.
 */
interface CategoriesApi {

    @GET("categories")
    suspend fun categories(): ApiResponse<List<CategoryDto>>
}
