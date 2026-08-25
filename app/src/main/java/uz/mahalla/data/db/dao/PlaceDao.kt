package uz.mahalla.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import uz.mahalla.data.db.entity.PlaceEntity

@Dao
interface PlaceDao {

    @Query("SELECT * FROM places ORDER BY distanceMeters ASC")
    fun observeAll(): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM places WHERE category = :category ORDER BY distanceMeters ASC")
    fun observeByCategory(category: String): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM places WHERE id = :id")
    suspend fun byId(id: String): PlaceEntity?

    @Upsert
    suspend fun upsert(places: List<PlaceEntity>)

    @Query("DELETE FROM places")
    suspend fun clear()
}
