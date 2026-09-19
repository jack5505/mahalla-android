package uz.mahalla.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import uz.mahalla.data.db.entity.AnalyticsEventEntity

/** Очередь `analytics/events` на диске (issue #226). */
@Dao
interface AnalyticsEventDao {

    @Insert
    suspend fun insert(event: AnalyticsEventEntity)

    /** Самые старые первыми — сервер не должен увидеть воронку в обратном порядке. */
    @Query("SELECT * FROM analytics_events ORDER BY id ASC LIMIT :limit")
    suspend fun oldest(limit: Int): List<AnalyticsEventEntity>

    @Query("DELETE FROM analytics_events WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** Отбрасывает то, что сервер и так не примет (`occurredAt` не старше 30 суток). */
    @Query("DELETE FROM analytics_events WHERE enqueuedAtEpochSecond < :thresholdEpochSecond")
    suspend fun deleteOlderThan(thresholdEpochSecond: Long): Int

    @Query("SELECT COUNT(*) FROM analytics_events")
    suspend fun count(): Int
}
