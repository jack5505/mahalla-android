package uz.mahalla.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import uz.mahalla.data.db.entity.PlaceCategoryEntity

/**
 * Абстрактный класс, а не интерфейс: [replaceAll] — метод с телом под
 * `@Transaction`, чтобы между удалением и вставкой подписчики [observeAll] не
 * увидели пустой список и не мигнули фолбэком.
 */
@Dao
abstract class PlaceCategoryDao {

    /** Порядок задаёт сервер (`sortOrder`); код — только чтобы порядок был устойчив. */
    @Query("SELECT * FROM place_categories ORDER BY sortOrder ASC, code ASC")
    abstract fun observeAll(): Flow<List<PlaceCategoryEntity>>

    @Query("SELECT * FROM place_categories ORDER BY sortOrder ASC, code ASC")
    abstract suspend fun all(): List<PlaceCategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(items: List<PlaceCategoryEntity>)

    @Query("DELETE FROM place_categories")
    abstract suspend fun clear()

    /** Ответ сервера — полный список, поэтому кэш заменяется целиком. */
    @Transaction
    open suspend fun replaceAll(items: List<PlaceCategoryEntity>) {
        clear()
        insert(items)
    }
}
