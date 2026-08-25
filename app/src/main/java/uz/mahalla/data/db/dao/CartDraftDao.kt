package uz.mahalla.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import uz.mahalla.data.db.entity.CartDraftItemEntity

@Dao
interface CartDraftDao {

    @Query("SELECT * FROM cart_draft_items WHERE placeId = :placeId ORDER BY name ASC")
    fun observe(placeId: String): Flow<List<CartDraftItemEntity>>

    @Query("SELECT SUM(priceSum * quantity) FROM cart_draft_items WHERE placeId = :placeId")
    suspend fun total(placeId: String): Long?

    @Upsert
    suspend fun upsert(item: CartDraftItemEntity)

    @Query("DELETE FROM cart_draft_items WHERE placeId = :placeId AND productId = :productId")
    suspend fun remove(placeId: String, productId: String)

    @Query("DELETE FROM cart_draft_items WHERE placeId = :placeId")
    suspend fun clear(placeId: String)
}
