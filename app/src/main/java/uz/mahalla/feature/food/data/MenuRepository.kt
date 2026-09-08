package uz.mahalla.feature.food.data

import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.map
import uz.mahalla.data.network.payload
import uz.mahalla.feature.food.domain.Menu
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Меню заведения (эпик 5.1).
 *
 * Меню **не кэшируется** в Room в отличие от каталога: стоп-лист меняется в
 * течение дня, и показать вчерашнее меню офлайн значит дать положить в корзину
 * то, чего на кухне нет.
 *
 * Промокода здесь больше нет: проверить код бэкенд умеет
 * (`GET promotions/check`), но приложить его к заказу нечем — в
 * `PlaceOrderRequest` поля под код не существует. Показывать скидку, которой
 * в счёте не будет, — врать про деньги.
 */
interface MenuRepository {

    suspend fun menu(placeId: String): ApiResult<Menu>
}

@Singleton
class DefaultMenuRepository @Inject constructor(
    private val api: FoodApi,
) : MenuRepository {

    override suspend fun menu(placeId: String): ApiResult<Menu> =
        apiCall { api.menu(placeId).payload() }.map { sections -> sections.toMenu(placeId) }
}
