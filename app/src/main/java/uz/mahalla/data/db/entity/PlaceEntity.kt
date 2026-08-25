package uz.mahalla.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Кэш каталога мест (эпик 1.4): по нему открывается discovery без сети.
 * `updatedAtEpochSeconds` нужен, чтобы отличать свежий кэш от устаревшего.
 */
@Entity(
    tableName = "places",
    indices = [Index("category")],
)
data class PlaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,
    val rating: Double,
    val distanceMeters: Int,
    val isOpenNow: Boolean,
    val updatedAtEpochSeconds: Long,
)
