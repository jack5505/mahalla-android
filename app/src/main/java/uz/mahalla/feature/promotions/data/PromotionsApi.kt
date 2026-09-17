package uz.mahalla.feature.promotions.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import uz.mahalla.data.network.ApiResponse

/**
 * Акции (контроллер `promotion`, issue #104).
 *
 * Контракт снят со стенда (`/v3/api-docs` + curl'ы 2026-09-04):
 *
 * | ручка | ответ | без токена |
 * |---|---|---|
 * | `GET promotions/platform?page&size` | конверт + `PageResponsePromotion` | **`200`** |
 * | `GET promotions/places/{placeId}` | конверт, `data: [Promotion]` | **`200`** |
 *
 * Обе читающие ручки **анонимны** — акции видит и тот, кто ещё не вошёл. А вот
 * гео-заголовки обязательны: без `X-Geo-Lat`/`X-Geo-Lng` приходит
 * `403 GEO_PERMISSION_REQUIRED`. Их ставит `GeoHeaderInterceptor` на обоих
 * клиентах (issue #53), так что вопрос закрыт сам собой. API собирается на
 * **основном** Retrofit: лишний `Authorization` читающим ручкам не мешает, а
 * «голый» `@RefreshClient` понадобился бы только ради его отсутствия.
 *
 * `placeId` — uuid: `promotions/places/1` отвечает `400 TYPE_MISMATCH`.
 *
 * `GET promotions/check` не используется: проверяет промокод, а применить его
 * в заказе нечем — поля под код в `PlaceOrderRequest` нет (issue #9).
 *
 * **`POST places/{placeId}`** (issue #252, владелец заводит акцию) —
 * доступна из «Моих заведений» тем же приёмом, что и `POST
 * pharmacy/.../products`: без захода в неслитую бизнес-панель (эпик #16).
 * Тело не проверено живым запросом (нужен Bearer владельца, которого в
 * песочнице нет) — поля выведены из уже подтверждённых полей ответа
 * [PromotionDto] того же контроллера, а не угаданы по аналогии с другой
 * вертикалью. При расхождении смотреть `docs/API-CONTRACT.md`.
 */
interface PromotionsApi {

    @GET("promotions/platform")
    suspend fun platform(
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ApiResponse<PromotionPageDto>

    @GET("promotions/places/{placeId}")
    suspend fun placePromotions(@Path("placeId") placeId: String): ApiResponse<List<PromotionDto>>

    @POST("promotions/places/{placeId}")
    suspend fun create(
        @Path("placeId") placeId: String,
        @Body body: CreatePromotionRequest,
    ): ApiResponse<PromotionDto>
}

/**
 * Тело `POST promotions/places/{placeId}` — имя схемы `/v3/api-docs` не
 * называет (не проверено живым запросом), поля повторяют [PromotionDto] того
 * же контроллера: заводящая ручка и читающая описывают одну сущность.
 */
@Serializable
data class CreatePromotionRequest(
    @SerialName("title") val title: String,
    @SerialName("description") val description: String? = null,
    @SerialName("promoType") val promoType: String,
    @SerialName("discountPercent") val discountPercent: Int? = null,
    @SerialName("discountAmount") val discountAmount: Long? = null,
    @SerialName("minOrderAmount") val minOrderAmount: Long? = null,
    @SerialName("promoCode") val promoCode: String? = null,
)

/** `PageResponsePromotion`. */
@Serializable
data class PromotionPageDto(
    @SerialName("content") val content: List<PromotionDto> = emptyList(),
    @SerialName("page") val page: Int? = null,
    @SerialName("size") val size: Int? = null,
    @SerialName("totalElements") val totalElements: Long? = null,
    @SerialName("totalPages") val totalPages: Int? = null,
    @SerialName("first") val first: Boolean? = null,
    @SerialName("last") val last: Boolean? = null,
)

/**
 * `Promotion`. Все поля необязательные: отсутствие любого из них — не повод
 * показать экран ошибки вместо блока акций.
 *
 * `isActive` и `isPlatformWide` принимаются и под именами без префикса:
 * Jackson сериализует `boolean isX` то так, то так, в зависимости от геттера,
 * а ошибка здесь спрятала бы все акции разом (то же правило, что у `isRead` в
 * issue #81 и `isAvailable` в issue #94).
 *
 * `bannerUrl` объявлен, но в домен не доезжает: загрузчика изображений в
 * проекте по-прежнему нет, и показывать баннер пока нечем. Поле документирует
 * контракт — как `imageUrl` уведомления.
 */
@Serializable
data class PromotionDto(
    @SerialName("id") val id: String? = null,
    @SerialName("placeId") val placeId: String? = null,
    @SerialName("ownerId") val ownerId: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("promoType") val promoType: String? = null,
    @SerialName("discountPercent") val discountPercent: Int? = null,
    @SerialName("discountAmount") val discountAmount: Long? = null,
    @SerialName("minOrderAmount") val minOrderAmount: Long? = null,
    @SerialName("promoCode") val promoCode: String? = null,
    @SerialName("bannerUrl") val bannerUrl: String? = null,
    @SerialName("startedAt") val startedAt: String? = null,
    @SerialName("endedAt") val endedAt: String? = null,
    @SerialName("isActive") val isActive: Boolean? = null,
    @SerialName("active") val active: Boolean? = null,
    @SerialName("isPlatformWide") val isPlatformWide: Boolean? = null,
    @SerialName("platformWide") val platformWide: Boolean? = null,
    @SerialName("valid") val valid: Boolean? = null,
)
