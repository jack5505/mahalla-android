package uz.mahalla.feature.food.data

import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.map
import uz.mahalla.core.result.runCatchingCancellable
import uz.mahalla.data.db.dao.PlaceDao
import uz.mahalla.data.network.payload
import uz.mahalla.feature.food.domain.Menu
import uz.mahalla.feature.food.domain.PromoCode
import java.util.Locale
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

    /**
     * Проверка промокода на сервере. `null` в успешном результате — код
     * существует, но к этому заказу не применяется (`valid: false`).
     */
    suspend fun promo(placeId: String, code: String, subtotalSum: Long): ApiResult<PromoCode?>
}

@Singleton
class DefaultMenuRepository @Inject constructor(
    private val api: FoodApi,
    private val placeDao: PlaceDao,
) : MenuRepository {

    /**
     * Название заведения бэкенд в меню не отдаёт, а показать его надо в шапке
     * меню и в корзине. Берём из кэша каталога: в меню приходят с карточки
     * места, то есть запись там уже лежит. Нет записи — пустое имя, экран
     * покажет общий заголовок и не соврёт.
     */
    override suspend fun menu(placeId: String): ApiResult<Menu> =
        apiCall { api.menu(placeId).payload() }
            .map { sections -> sections.toMenu(placeId, placeName(placeId)) }

    override suspend fun promo(
        placeId: String,
        code: String,
        subtotalSum: Long,
    ): ApiResult<PromoCode?> =
        apiCall {
            // Locale.ROOT обязателен: на турецкой локали устройства `i` уехал бы
            // в `İ` и правильный код улетел бы на сервер испорченным.
            api.checkPromo(
                code = code.trim().uppercase(Locale.ROOT),
                placeId = placeId,
                orderAmountSum = subtotalSum,
            ).payload()
        }.map { dto ->
            // Скидка 0 при `valid: true` — то же самое, что «не подошёл»:
            // применить код и не изменить сумму человек прочитает как поломку.
            if (!dto.valid || dto.discountAmount <= 0) null
            else dto.toDomain(code = code.trim().uppercase(Locale.ROOT), subtotalSum = subtotalSum)
        }

    private suspend fun placeName(placeId: String): String =
        runCatchingCancellable { placeDao.byId(placeId)?.name }.getOrNull().orEmpty()
}
