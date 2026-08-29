package uz.mahalla.feature.food.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import uz.mahalla.data.db.dao.CartDraftDao
import uz.mahalla.data.db.entity.CartDraftItemEntity
import uz.mahalla.feature.food.domain.Cart
import uz.mahalla.feature.food.domain.CartCalculator
import uz.mahalla.feature.food.domain.CartLine
import uz.mahalla.feature.food.domain.PromoCode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Корзина (эпик 5.2). Позиции живут в Room — черновик обязан пережить закрытие
 * приложения, — а применённый промокод только в памяти: его валидность зависит
 * от состава заказа и срока действия, и восстанавливать его из вчерашнего
 * черновика значит показать скидку, которой уже нет.
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
    suspend fun add(placeId: String, placeName: String, deliverySum: Long, line: CartLine)

    /**
     * Полная замена корзины (повтор заказа): прежний черновик исчезает, новый
     * появляется целиком — одной транзакцией, а не «сначала почистить, потом
     * добавить».
     */
    suspend fun replace(
        placeId: String,
        placeName: String,
        deliverySum: Long,
        lines: List<CartLine>,
    )

    suspend fun setQuantity(placeId: String, lineId: String, quantity: Int)

    suspend fun remove(placeId: String, lineId: String)

    suspend fun clear(placeId: String)

    suspend fun clearAll()

    fun applyPromo(promo: PromoCode?)
}

@Singleton
class DefaultCartRepository @Inject constructor(
    private val dao: CartDraftDao,
) : CartRepository {

    private val promoState = MutableStateFlow<PromoCode?>(null)

    val promo: StateFlow<PromoCode?> = promoState.asStateFlow()

    override fun cart(placeId: String): Flow<Cart> =
        combine(dao.observe(placeId), promoState) { rows, promo ->
            rows.toCart(placeId).copy(promo = promo)
        }

    override suspend fun activePlaceId(): String? = dao.activePlaceId()

    override suspend fun snapshot(placeId: String): Cart =
        dao.items(placeId).toCart(placeId).copy(promo = promoState.value)

    override suspend fun add(
        placeId: String,
        placeName: String,
        deliverySum: Long,
        line: CartLine,
    ) {
        val existing = dao.line(placeId, line.id)
        val quantity = ((existing?.quantity ?: 0) + line.quantity)
            .coerceIn(1, CartCalculator.MAX_QUANTITY)
        dao.upsert(
            line.copy(quantity = quantity).toEntity(
                placeId = placeId,
                placeName = placeName,
                deliverySum = deliverySum,
            ),
        )
    }

    /**
     * Замена корзины целиком. Промокод снимается: он был выдан под прежний
     * состав.
     */
    override suspend fun replace(
        placeId: String,
        placeName: String,
        deliverySum: Long,
        lines: List<CartLine>,
    ) {
        dao.replaceAll(
            lines.distinctBy(CartLine::id).map { line ->
                line
                    .copy(quantity = line.quantity.coerceIn(1, CartCalculator.MAX_QUANTITY))
                    .toEntity(placeId = placeId, placeName = placeName, deliverySum = deliverySum)
            },
        )
        promoState.value = null
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

    /** Очистка корзины снимает и промокод: он был выдан под этот состав. */
    override suspend fun clear(placeId: String) {
        dao.clear(placeId)
        promoState.value = null
    }

    override suspend fun clearAll() {
        dao.clearAll()
        promoState.value = null
    }

    override fun applyPromo(promo: PromoCode?) {
        promoState.value = promo
    }
}
