package uz.mahalla.feature.food.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import uz.mahalla.data.db.dao.CartDraftDao
import uz.mahalla.data.db.entity.CartDraftItemEntity
import uz.mahalla.feature.food.domain.Cart
import uz.mahalla.feature.food.domain.CartCalculator
import uz.mahalla.feature.food.domain.CartLine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Корзина (эпик 5.2). Позиции живут в Room: черновик обязан пережить закрытие
 * приложения.
 *
 * Промокода в корзине больше нет — приложить его к заказу бэкенду нечем
 * (см. `MenuRepository`).
 */
interface CartRepository {

    fun cart(placeId: String): Flow<Cart>

    /** Заведение начатого черновика; `null` — корзина пуста. */
    suspend fun activePlaceId(): String?

    suspend fun snapshot(placeId: String): Cart

    /**
     * Добавление позиции. Такая же строка (та же позиция, те же модификаторы)
     * увеличивает количество, а не появляется второй раз.
     */
    suspend fun add(placeId: String, placeName: String, line: CartLine)

    /**
     * Полная замена корзины (повтор заказа): прежний черновик исчезает, новый
     * появляется целиком — одной транзакцией, а не «сначала почистить, потом
     * добавить».
     */
    suspend fun replace(placeId: String, placeName: String, lines: List<CartLine>)

    suspend fun setQuantity(placeId: String, lineId: String, quantity: Int)

    suspend fun remove(placeId: String, lineId: String)

    suspend fun clear(placeId: String)

    suspend fun clearAll()
}

@Singleton
class DefaultCartRepository @Inject constructor(
    private val dao: CartDraftDao,
) : CartRepository {

    override fun cart(placeId: String): Flow<Cart> =
        dao.observe(placeId).map { rows -> rows.toCart(placeId) }

    override suspend fun activePlaceId(): String? = dao.activePlaceId()

    override suspend fun snapshot(placeId: String): Cart = dao.items(placeId).toCart(placeId)

    override suspend fun add(placeId: String, placeName: String, line: CartLine) {
        val existing = dao.line(placeId, line.id)
        val quantity = ((existing?.quantity ?: 0) + line.quantity)
            .coerceIn(1, CartCalculator.MAX_QUANTITY)
        dao.upsert(
            line.copy(quantity = quantity).toEntity(placeId = placeId, placeName = placeName),
        )
    }

    /** Замена корзины целиком (повтор заказа). */
    override suspend fun replace(placeId: String, placeName: String, lines: List<CartLine>) {
        dao.replaceAll(
            lines.distinctBy(CartLine::id).map { line ->
                line
                    .copy(quantity = line.quantity.coerceIn(1, CartCalculator.MAX_QUANTITY))
                    .toEntity(placeId = placeId, placeName = placeName)
            },
        )
    }

    /**
     * Ноль и меньше — удаление строки: держать в базе строку с нулевым
     * количеством незачем, а «−» на последней единице ведёт именно сюда.
     */
    override suspend fun setQuantity(placeId: String, lineId: String, quantity: Int) {
        if (quantity <= 0) {
            dao.remove(placeId, lineId)
            return
        }
        val existing: CartDraftItemEntity = dao.line(placeId, lineId) ?: return
        dao.upsert(existing.copy(quantity = quantity.coerceAtMost(CartCalculator.MAX_QUANTITY)))
    }

    override suspend fun remove(placeId: String, lineId: String) = dao.remove(placeId, lineId)

    override suspend fun clear(placeId: String) = dao.clear(placeId)

    override suspend fun clearAll() = dao.clearAll()
}
