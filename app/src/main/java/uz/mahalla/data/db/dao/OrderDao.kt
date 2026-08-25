package uz.mahalla.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import uz.mahalla.data.db.entity.OrderEntity

@Dao
interface OrderDao {

    @Query("SELECT * FROM orders ORDER BY createdAtEpochSeconds DESC")
    fun observeAll(): Flow<List<OrderEntity>>

    @Query("SELECT * FROM orders WHERE id = :id")
    suspend fun byId(id: String): OrderEntity?

    @Upsert
    suspend fun upsert(orders: List<OrderEntity>)

    @Query("DELETE FROM orders")
    suspend fun clear()
}
