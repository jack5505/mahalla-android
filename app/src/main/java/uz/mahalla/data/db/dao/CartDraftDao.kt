package uz.mahalla.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import uz.mahalla.data.db.entity.CartDraftItemEntity

@Dao
interface CartDraftDao {

    @Query("SELECT * FROM cart_draft_items WHERE placeId = :placeId ORDER BY name ASC, lineId ASC")
    fun observe(placeId: String): Flow<List<CartDraftItemEntity>>

    @Query("SELECT * FROM cart_draft_items WHERE placeId = :placeId ORDER BY name ASC, lineId ASC")
    suspend fun items(placeId: String): List<CartDraftItemEntity>

    @Query("SELECT * FROM cart_draft_items WHERE placeId = :placeId AND lineId = :lineId")
    suspend fun line(placeId: String, lineId: String): CartDraftItemEntity?

    @Query("SELECT SUM(priceSum * quantity) FROM cart_draft_items WHERE placeId = :placeId")
    suspend fun total(placeId: String): Long?

    /**
     * Заведение начатого черновика. Корзина всегда в рамках одного заведения,
     * поэтому перед добавлением позиции из другого места нужно спросить, не
     * очистить ли прежнюю.
     */
    @Query("SELECT placeId FROM cart_draft_items LIMIT 1")
    suspend fun activePlaceId(): String?

    @Upsert
    suspend fun upsert(item: CartDraftItemEntity)

    @Upsert
    suspend fun upsertAll(items: List<CartDraftItemEntity>)

    /**
     * Полная замена черновика — очистка и запись в одной транзакции. Двумя
     * вызовами (сначала `clearAll`, потом цикл `upsert`) падение посередине
     * оставляло человека и без прежней корзины, и без новой.
     */
    @Transaction
    suspend fun replaceAll(items: List<CartDraftItemEntity>) {
        clearAll()
        upsertAll(items)
    }

    @Query("DELETE FROM cart_draft_items WHERE placeId = :placeId AND lineId = :lineId")
    suspend fun remove(placeId: String, lineId: String)

    @Query("DELETE FROM cart_draft_items WHERE placeId = :placeId")
    suspend fun clear(placeId: String)

    @Query("DELETE FROM cart_draft_items")
    suspend fun clearAll()
}
