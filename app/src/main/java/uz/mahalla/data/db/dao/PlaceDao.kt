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

    /**
     * Разовое чтение для фоллбэка репозитория: там нужен снимок кэша на момент
     * сетевой ошибки, а не подписка.
     */
    @Query("SELECT * FROM places ORDER BY distanceMeters ASC LIMIT :limit")
    suspend fun nearest(limit: Int): List<PlaceEntity>

    @Query("SELECT * FROM places WHERE category = :category ORDER BY distanceMeters ASC LIMIT :limit")
    suspend fun nearestByCategory(category: String, limit: Int): List<PlaceEntity>

    @Query("SELECT * FROM places WHERE id = :id")
    suspend fun byId(id: String): PlaceEntity?

    @Upsert
    suspend fun upsert(places: List<PlaceEntity>)

    @Query("DELETE FROM places")
    suspend fun clear()

    /** Место удалили на сервере — держать его копию в офлайн-выдаче незачем. */
    @Query("DELETE FROM places WHERE id = :id")
    suspend fun delete(id: String)

    /** Чистка протухшего кэша: строки старше порога больше не показываем. */
    @Query("DELETE FROM places WHERE updatedAtEpochSeconds < :updatedBeforeEpochSeconds")
    suspend fun deleteStale(updatedBeforeEpochSeconds: Long)
}
