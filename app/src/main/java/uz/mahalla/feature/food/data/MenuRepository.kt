package uz.mahalla.feature.food.data

import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.map
import uz.mahalla.feature.food.domain.Menu
import uz.mahalla.feature.food.domain.PromoCode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Меню заведения и промокоды (эпик 5.1/5.2).
 *
 * Меню **не кэшируется** в Room в отличие от каталога: стоп-лист меняется в
 * течение дня, и показать вчерашнее меню офлайн значит дать положить в корзину
 * то, чего на кухне нет.
 */
interface MenuRepository {

    suspend fun menu(placeId: String): ApiResult<Menu>

    /** Проверка промокода на сервере: скидку считает тот, кто выставит счёт. */
    suspend fun promo(placeId: String, code: String, subtotalSum: Long): ApiResult<PromoCode>
}

@Singleton
class DefaultMenuRepository @Inject constructor(
    private val api: FoodApi,
) : MenuRepository {

    override suspend fun menu(placeId: String): ApiResult<Menu> =
        apiCall { api.menu(placeId) }.map { it.toDomain(placeId) }

    override suspend fun promo(
        placeId: String,
        code: String,
        subtotalSum: Long,
    ): ApiResult<PromoCode> =
        apiCall {
            api.promo(placeId, PromoRequestDto(code = code.trim().uppercase(), subtotal = subtotalSum))
        }.map(PromoDto::toDomain)
}
